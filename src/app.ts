// media.gftd.ai — the A→B medium (ADR-2606161200 split: news=A, media=medium).
//
// Takes primary sources collected by news.gftd.ai (A) and connects them to
// subjects (B): resolve candidate subjects, score the A→B bridge, generate a
// B-framed brief grounded in A, and deliver it as an attributed genre-desk post.
//
// Interop: this TS shell owns async IO + the SDK calls deps-score scans; the
// ../js/media.js ClojureScript module provides pure logic (validation, kotoba
// Datomic EDN tx/query, A→B candidate ranking + bridge scoring, post text).
// news's :news/* source corpus (A) is read cross-graph from the news graph;
// media writes :media.link/* (A→B) + :media.subject/* (B) to the media graph.

import {
  asAgentTool,
  createWorkerExport,
  nsid,
  withCapabilityTags,
  withOCELEvent,
  type ComAtprotoSyncSubscribeReposCommit,
  type HostSDK,
} from "@gftd/magatama-host-sdk";
import * as media from "../js/media.js";

type Env = {
  MEDIA_VERSION?: string;
  MEDIA_ACTOR_DID?: string;
  KOTOBA_BACKEND_URL?: string;
  KOTOBA_DATOMIC_NSID?: string;
  KOTOBA_GRAPH?: string;   // media-link-v1
  NEWS_GRAPH?: string;     // news-intel-v1 (A corpus)
  KOTOBA_BEARER?: string;
  LITELLM_URL?: string;
  MURAKUMO_DEFAULT_MODEL?: string;
  MURAKUMO_API_KEY?: string;
  MEDIA_POD_URL?: string;
  // Cloudflare Queue producer binding (hourly analysis fan-out).
  ANALYSIS_QUEUE?: { send(msg: unknown): Promise<void>; sendBatch(msgs: { body: unknown }[]): Promise<void> };
};

type AnalysisJob = { sourceId: string; genre?: string };
const enc = (o: unknown): Uint8Array => new TextEncoder().encode(JSON.stringify(o));

// Map a source topic/title to a genre desk (default tech). Keyword heuristic;
// refined later by the pod / LLM.
function inferGenre(text?: string): string {
  const t = (text ?? "").toLowerCase();
  if (/policy|law|government|regulat|法|政策|行政|通知/.test(t)) return "policy";
  if (/labor|worker|wage|welfare|union|労働|福祉|賃金/.test(t)) return "labor";
  if (/market|stock|earnings|economy|inflation|on-chain|市場|経済|決算/.test(t)) return "markets";
  if (/incident|accident|disaster|alert|emergency|事故|災害|警報/.test(t)) return "incident";
  if (/culture|film|music|art|creator|game|anime|文化|音楽|芸術/.test(t)) return "culture";
  if (/local|city|town|neighborhood|community|地域|自治体|市町村/.test(t)) return "local";
  if (/science|study|research|health|medical|clinical|科学|研究|医療|健康/.test(t)) return "science";
  if (/tech|software|ai|model|chip|hardware|技術|ソフト/.test(t)) return "tech";
  return "tech";
}

// ── crypto (Web Crypto → content-addressed ids + graph CID) ──────────────────

async function sha256Hex(s: string): Promise<string> {
  const b = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
  return Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
}
const B32 = "abcdefghijklmnopqrstuvwxyz234567";
function base32lower(bytes: Uint8Array): string {
  let bits = 0, value = 0, out = "";
  for (const b of bytes) { value = (value << 8) | b; bits += 8; while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5; } }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}
async function graphCidForLabel(label: string): Promise<string> {
  if (/^b[a-z2-7]{58,80}$/.test(label)) return label;
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(label)));
  const cid = new Uint8Array(4 + digest.length); cid.set([0x01, 0x71, 0x12, 0x20], 0); cid.set(digest, 4);
  return "b" + base32lower(cid);
}
const _cid: Record<string, string> = {};
async function graphCid(label: string): Promise<string> {
  if (!_cid[label]) _cid[label] = await graphCidForLabel(label);
  return _cid[label];
}

// ── kotoba Datomic XRPC ──────────────────────────────────────────────────────

async function kotobaXrpc(env: Env, method: string, body: Record<string, unknown>): Promise<any> {
  const base = (env.KOTOBA_BACKEND_URL ?? "https://kotobase.net").replace(/\/+$/, "");
  const ns = env.KOTOBA_DATOMIC_NSID ?? "ai.gftd.apps.kotobase.datomic";
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (env.KOTOBA_BEARER) headers.authorization = `Bearer ${env.KOTOBA_BEARER}`;
  const r = await fetch(`${base}/xrpc/${ns}.${method}`, { method: "POST", headers, body: JSON.stringify(body) });
  if (!r.ok) throw new Error(`kotoba ${method}: ${r.status} ${(await r.text().catch(() => "")).slice(0, 200)}`);
  return r.json().catch(() => ({}));
}
async function dmTransact(env: Env, txEdn: string): Promise<any> {
  return kotobaXrpc(env, "transact", { graph: await graphCid(env.KOTOBA_GRAPH ?? "media-link-v1"), tx_edn: txEdn });
}
async function dmQuery(env: Env, graphLabel: string, queryEdn: string): Promise<any[]> {
  const res = await kotobaXrpc(env, "q", { graph: await graphCid(graphLabel), query_edn: queryEdn });
  return (res?.rows_edn ?? res?.rows ?? []) as any[];
}

// ── litellm (B-framed generation; model id from env SSoT) ────────────────────

async function llmComplete(env: Env, system: string, user: string, maxTokens = 320): Promise<string> {
  const url = (env.LITELLM_URL ?? "https://api.murakumo.cloud/v1").replace(/\/+$/, "");
  const model = env.MURAKUMO_DEFAULT_MODEL ?? "murakumo-main";
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (env.MURAKUMO_API_KEY) headers.authorization = `Bearer ${env.MURAKUMO_API_KEY}`;
  const r = await fetch(`${url}/chat/completions`, {
    method: "POST", headers,
    body: JSON.stringify({ model, messages: [{ role: "system", content: system }, { role: "user", content: user }], max_tokens: maxTokens, temperature: 0.4 }),
  });
  if (!r.ok) throw new Error(`litellm: ${r.status}`);
  const j = await r.json().catch(() => ({} as any));
  return j?.choices?.[0]?.message?.content ?? "";
}

// ── helpers ──────────────────────────────────────────────────────────────────

const decode = (b: Uint8Array): any => JSON.parse(new TextDecoder().decode(b) || "{}");
const nowISO = (): string => new Date().toISOString();
const MEDIA_LABEL = (env: Env) => env.KOTOBA_GRAPH ?? "media-link-v1";
const NEWS_LABEL = (env: Env) => env.NEWS_GRAPH ?? "news-intel-v1";

function postAs(sdk: HostSDK, did: string, text: string): void {
  sdk.pds.dispatch({ type: "app.bsky.feed.postAs", payload: { did, text, embed: "" } });
}

/** Resolve A: a news primary source by id (cross-graph read), or an inline source. */
async function resolveSource(env: Env, body: any): Promise<any | null> {
  if (body.source && typeof body.source === "object") return body.source;
  if (!body.sourceId) return null;
  // news source pulled from the news graph (:news/* schema).
  const q = `[:find (pull ?e [*]) :where [?e :news/id ${JSON.stringify(String(body.sourceId))}]]`;
  const rows: any[] = media.shapeRows(await dmQuery(env, NEWS_LABEL(env), q));
  if (!rows.length) return null;
  const s = rows[0];
  return { id: s["news/id"], title: s["news/title"], summary: s["news/summary"], text: s["news/text"],
           url: s["news/url"], topic: s["news/topic"], region: s["news/region"], sourceName: s["news/sourceName"] };
}

// ── handlers ─────────────────────────────────────────────────────────────────

async function hRegisterSubject(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const a = decode(body);
  const v = media.validateSubject(a);
  if (!v.valid) return { ok: false, error: v.error };
  const id = `subj-${a.kind}-${(await sha256Hex(String(a.label))).slice(0, 16)}`;
  await dmTransact(env, media.subjectToTxEdn({ ...a, id, createdAt: nowISO() }));
  return { ok: true, subjectId: id };
}

// Register the canonical per-genre subject (B) seeds so autopilot has targets.
// Idempotent (subjectId = subj-<kind>-<hash(label)>).
async function hSeedSubjects(sdk: HostSDK, env: Env, _body: Uint8Array): Promise<unknown> {
  const seeds = media.seedSubjects();
  const ids: string[] = [];
  for (const s of seeds) {
    const r: any = await hRegisterSubject(sdk, env, enc({ kind: s.kind, label: s.label, topic: s.topic }));
    if (r.ok) ids.push(r.subjectId);
  }
  return { ok: true, seeded: ids.length, subjectIds: ids };
}

async function hLinkSourceToSubject(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const a = decode(body);
  const v = media.validateLink(a);
  if (!v.valid) return { ok: false, error: v.error };
  const src = await resolveSource(env, a);
  if (!src) return { ok: false, error: "source (A) not found: provide sourceId (news) or inline source" };
  const subjText = [a.subjectLabel, a.subjectTopic].filter(Boolean).join(" ");
  const scores = media.scoreBridge({ sourceText: [src.title, src.summary, src.text].filter(Boolean).join(" "), subjectText: subjText });
  const linkId = `lnk-${(await sha256Hex(`${src.id ?? src.url}|${a.subjectId}|${a.genre}`)).slice(0, 24)}`;
  const relation = a.relation ?? media.inferRelation(a.genre, [src.title, src.summary, src.text].filter(Boolean).join(" "));
  const link = {
    id: linkId, genre: a.genre, relation,
    sourceId: src.id ?? null, sourceUrl: src.url ?? null, subjectId: a.subjectId,
    writerDid: media.deskWriterDid(a.genre),
    bridgeScores: scores.bridgeScores, arbitrage: scores.arbitrage, relevance: scores.relevance,
    provenance: { sourceName: src.sourceName, sourceUrl: src.url }, createdAt: nowISO(),
  };
  await dmTransact(env, media.linkToTxEdn(link));
  return { ok: true, linkId, relevance: scores.relevance, arbitrage: scores.arbitrage, bridgeScores: scores.bridgeScores };
}

async function loadLink(env: Env, linkId: string): Promise<any | null> {
  const rows: any[] = media.shapeRows(await dmQuery(env, MEDIA_LABEL(env), media.qByLinkId(linkId)));
  return rows.length ? rows[0] : null;
}

async function hGenerateBrief(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const a = decode(body);
  if (!a.linkId) return { ok: false, error: "linkId required" };
  const link = await loadLink(env, a.linkId);
  if (!link) return { ok: false, error: "link not found" };
  const src = await resolveSource(env, { sourceId: link["media.link/sourceId"], source: a.source });
  const genre = link["media.link/genre"];
  // Target language: explicit > subject's lang > source's lang > en (B-framed,
  // so the medium spans the language gap too — 100+ ISO 639-1 langs supported).
  const lang = media.normalizeLang(a.lang ?? a.subjectLang ?? src?.lang ?? "en") ?? "en";
  let title = a.title ?? "", brief = a.brief ?? "";
  if (!title.trim() || !brief.trim()) {
    try {
      const desk = media.deskProfile(genre);
      const deskSteer = desk ? `Audience: ${desk.audience}. Tone: ${desk.tone}.` : "";
      const out = await llmComplete(env,
        `You are the ${genre} desk of media.gftd.ai. Connect the primary source to the subject audience. ${deskSteer} ${media.langDirective(lang)} Output exactly two lines: line 1 = a headline framed for the subject; line 2 = a 1-2 sentence brief, source-grounded, no speculation.`,
        `Subject (B): ${a.subjectLabel ?? link["media.link/subjectId"]}\nSource (A): ${src?.title ?? ""} — ${src?.url ?? ""}\n${src?.summary ?? src?.text ?? ""}`.slice(0, 4000));
      const lines = out.split("\n").map((s) => s.trim()).filter(Boolean);
      title = title || (lines[0] ?? src?.title ?? "");
      brief = brief || (lines.slice(1).join(" ") || src?.summary || "");
    } catch (e) { console.warn("[generateBrief] llm failed:", e); title = title || src?.title || ""; brief = brief || src?.summary || ""; }
  }
  await dmTransact(env, media.linkToTxEdn({
    id: a.linkId, genre, sourceId: link["media.link/sourceId"], sourceUrl: link["media.link/sourceUrl"],
    subjectId: link["media.link/subjectId"], writerDid: link["media.link/writerDid"], title, brief, lang, createdAt: nowISO(),
  }));
  return { ok: true, linkId: a.linkId, title, brief, lang };
}

async function hPublishLink(sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const a = decode(body);
  if (!a.linkId) return { ok: false, error: "linkId required" };
  const link = await loadLink(env, a.linkId);
  if (!link) return { ok: false, error: "link not found" };
  const writerDid = link["media.link/writerDid"] ?? media.deskWriterDid(link["media.link/genre"]);
  const postText = a.socialPost && String(a.socialPost).trim() ? String(a.socialPost)
    : media.linkToPostText({ title: link["media.link/title"], brief: link["media.link/brief"], sourceUrl: link["media.link/sourceUrl"] });
  let delivered = false;
  try {
    postAs(sdk, writerDid, postText);
    const postUri = `at://${writerDid}/app.bsky.feed.post/${String(a.linkId).slice(4, 17)}`;
    await dmTransact(env, media.reconcileDeliveryTxEdn(a.linkId, postUri, nowISO()));
    delivered = true;
    return { ok: true, delivered, writerDid, postText, postUri };
  } catch (e) { console.warn("[publishLink] dispatch failed:", e); return { ok: true, delivered, writerDid, postText }; }
}

/** SDK-free A→B analysis for one source: candidate subjects → link → generate.
 *  No social delivery (so it runs from the queue consumer / scheduled context
 *  without a magatama SDK). hAutopilot adds delivery on top. */
async function runAnalysis(env: Env, opts: any): Promise<any> {
  const src = await resolveSource(env, opts);
  if (!src) return { ok: false, error: "source (A) not found" };
  const genre: string = opts.genre ?? inferGenre([src.title, src.topic, src.summary].filter(Boolean).join(" "));
  const subjects: any[] = media.shapeRows(await dmQuery(env, MEDIA_LABEL(env), media.qListSubjects(null)))
    .map((s: any) => ({ id: s["media.subject/id"], kind: s["media.subject/kind"], label: s["media.subject/label"], topic: s["media.subject/topic"], region: s["media.subject/region"], lang: s["media.subject/lang"] }));
  const cands: any[] = media.candidateSubjects({ source: src, subjects, min: Number(opts.minRelevance ?? 10), max: Number(opts.maxSubjects ?? 3) });
  const results: any[] = [];
  for (const c of cands) {
    const subj = subjects.find((s) => s.id === c.subjectId);
    const linkRes: any = await hLinkSourceToSubject(null as any, env, enc({
      sourceId: opts.sourceId, source: opts.source, subjectId: c.subjectId, genre, relation: opts.relation,
      subjectLabel: subj?.label, subjectTopic: subj?.topic,
    }));
    if (!linkRes.ok) { results.push(linkRes); continue; }
    await hGenerateBrief(null as any, env, enc({ linkId: linkRes.linkId, subjectLabel: subj?.label, subjectLang: subj?.lang, source: opts.source }));
    results.push({ linkId: linkRes.linkId, subjectId: c.subjectId, arbitrage: c.arbitrage });
  }
  return { ok: true, source: src.id ?? src.url, genre, linked: results.length, results };
}

/** Full medium pipeline for one source A: analysis + (optional) delivery. */
async function hAutopilot(sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const a = decode(body);
  const res = await runAnalysis(env, a);
  if (!res.ok) return res;
  if (a.publish) {
    for (const r of res.results) {
      if (r.linkId) { const p: any = await hPublishLink(sdk, env, enc({ linkId: r.linkId })); r.delivered = p.delivered; }
    }
  }
  return res;
}

// ── hourly producer: enqueue recent primary sources for analysis ─────────────

async function produceAnalysisJobs(env: Env): Promise<{ enqueued: number }> {
  if (!env.ANALYSIS_QUEUE) { console.warn("[scheduled] ANALYSIS_QUEUE not bound"); return { enqueued: 0 }; }
  const sinceMs = Date.now() - 70 * 60 * 1000; // ~last hour (+slack for cron jitter)
  const rows: any[] = media.shapeRows(await dmQuery(env, NEWS_LABEL(env), "[:find (pull ?e [*]) :where [?e :news/id ?id]]"));
  const recent = rows
    .filter((r: any) => { const c = r["news/createdAt"]; return c && Date.parse(String(c)) >= sinceMs; })
    .slice(0, 500);
  const msgs: { body: AnalysisJob }[] = recent.map((r: any) => ({
    body: { sourceId: String(r["news/id"]), genre: inferGenre([r["news/title"], r["news/topic"], r["news/summary"]].filter(Boolean).join(" ")) },
  }));
  for (let i = 0; i < msgs.length; i += 100) await env.ANALYSIS_QUEUE.sendBatch(msgs.slice(i, i + 100));
  return { enqueued: msgs.length };
}

function paginate(items: any[], b: any) {
  const offset = Math.max(0, Number(b.offset ?? 0) | 0);
  const limit = Math.min(100, Math.max(1, Number(b.limit ?? 50) | 0));
  return { slice: items.slice(offset, offset + limit), offset, limit, total: items.length };
}

async function hListLinks(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const b = decode(body);
  let links: any[] = media.shapeRows(await dmQuery(env, MEDIA_LABEL(env), media.qListLinks(b.genre ?? null)));
  links.sort((x, y) => String(y["media.link/createdAt"] ?? "").localeCompare(String(x["media.link/createdAt"] ?? "")));
  const { slice, offset, limit, total } = paginate(links, b);
  return { links: slice, items: slice.length, total, offset, limit };
}

async function hGetLink(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const b = decode(body);
  if (!b.linkId) return { error: "linkId required" };
  const link = await loadLink(env, b.linkId);
  return link ?? { error: "not found" };
}

async function hLinksForSubject(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const b = decode(body);
  if (!b.subjectId) return { error: "subjectId required" };
  const links: any[] = media.shapeRows(await dmQuery(env, MEDIA_LABEL(env), media.qLinksForSubject(String(b.subjectId))));
  const { slice, offset, limit, total } = paginate(links, b);
  return { links: slice, total, offset, limit };
}

async function hListSubjects(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const b = decode(body);
  const rows: any[] = media.shapeRows(await dmQuery(env, MEDIA_LABEL(env), media.qListSubjects(b.kind ?? null)));
  const { slice, offset, limit, total } = paginate(rows, b);
  return { subjects: slice, total, offset, limit };
}

// Follow news: each new primary source (A) triggers the medium pipeline.
function hOnCommit(sdk: HostSDK, env: Env, commit: ComAtprotoSyncSubscribeReposCommit): void {
  if (commit.action !== "create") return;
  if (commit.collection !== "ai.gftd.apps.news.source") return;
  // Heavy multi-subject linking is deferred to the pod; here we kick autopilot
  // for the single new source (best-effort, drained after the response).
  const rkey = (commit as any).rkey ?? "";
  void hAutopilot(sdk, env, new TextEncoder().encode(JSON.stringify({ sourceId: rkey, publish: false })))
    .catch((e) => console.warn("[onCommit] autopilot failed:", e));
}

// ── registration ─────────────────────────────────────────────────────────────

const _worker = createWorkerExport((sdk) => {
  const env = sdk.env as unknown as Env;
  sdk.app
    .command(nsid("ai.gftd.apps.media.registerSubject"), (_c, b) => hRegisterSubject(sdk, env, b),
      asAgentTool("Register a subject (B): cohort/org/place/market"), withCapabilityTags("write", "subject"))
    .command(nsid("ai.gftd.apps.media.seedSubjects"), (_c, b) => hSeedSubjects(sdk, env, b),
      asAgentTool("Register the canonical per-genre subject seeds"), withCapabilityTags("write", "subject"))
    .command(nsid("ai.gftd.apps.media.linkSourceToSubject"), (_c, b) => hLinkSourceToSubject(sdk, env, b),
      asAgentTool("Create a scored A→B medium link from a primary source to a subject"),
      withCapabilityTags("write", "link"), withOCELEvent("media.link"))
    .command(nsid("ai.gftd.apps.media.generateBrief"), (_c, b) => hGenerateBrief(sdk, env, b),
      asAgentTool("Generate a subject-framed brief for an A→B link (grounded in A)"), withCapabilityTags("write", "link"))
    .command(nsid("ai.gftd.apps.media.publishLink"), (_c, b) => hPublishLink(sdk, env, b),
      asAgentTool("Deliver an A→B link as an attributed genre-desk post"), withCapabilityTags("write", "link"))
    .command(nsid("ai.gftd.apps.media.autopilot"), (_c, b) => hAutopilot(sdk, env, b),
      asAgentTool("Run the full medium pipeline for one source: link→generate→deliver"),
      withCapabilityTags("write", "link"), withOCELEvent("media.autopilot"))
    .command(nsid("ai.gftd.apps.media.listLinks"), (_c, b) => hListLinks(sdk, env, b),
      asAgentTool("List A→B links (offset/limit)"), withCapabilityTags("query", "link"))
    .command(nsid("ai.gftd.apps.media.getLink"), (_c, b) => hGetLink(sdk, env, b),
      asAgentTool("Get an A→B link by id"), withCapabilityTags("query", "link"))
    .command(nsid("ai.gftd.apps.media.linksForSubject"), (_c, b) => hLinksForSubject(sdk, env, b),
      asAgentTool("List links delivered to a subject"), withCapabilityTags("query", "link"))
    .command(nsid("ai.gftd.apps.media.listSubjects"), (_c, b) => hListSubjects(sdk, env, b),
      asAgentTool("List registered subjects (B)"), withCapabilityTags("query", "subject"));
  sdk.app.onCommit((commit) => hOnCommit(sdk, env, commit));
});

// fetch + the hourly queue-scaled analysis (scheduled producer → queue consumer).
export default {
  fetch: _worker.fetch,
  // Hourly Cron Trigger: enqueue recent primary sources for analysis.
  async scheduled(_event: unknown, env: Env, ctx: { waitUntil(p: Promise<unknown>): void }): Promise<void> {
    ctx.waitUntil(
      produceAnalysisJobs(env)
        .then((r) => console.info("[scheduled] enqueued analysis jobs:", r.enqueued))
        .catch((e) => console.warn("[scheduled] producer failed:", e)),
    );
  },
  // Queue consumer: run the A→B analysis per source, bounded by max_concurrency.
  async queue(batch: { messages: Array<{ body: AnalysisJob; ack(): void; retry(): void }> }, env: Env): Promise<void> {
    for (const m of batch.messages) {
      try {
        await runAnalysis(env, { sourceId: m.body.sourceId, genre: m.body.genre, minRelevance: 10, maxSubjects: 3 });
        m.ack();
      } catch (e) {
        console.warn("[queue] analysis failed, retrying:", e);
        m.retry();
      }
    }
  },
};
