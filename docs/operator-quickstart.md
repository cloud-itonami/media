# Operator quickstart

This repository has **two faces** (`CLAUDE.md` names them): an A→B link medium
Worker, and `kouryaku` — a game-strategy EDN corpus with a public static site. Only
the second one runs today. The link medium builds but has never been deployed,
because `CLAUDE.md` records three unmet runtime preconditions.

This document walks the `kouryaku` pipeline end to end, because it works, and reports
what the walk found — including the first time one of its gates was made to refuse
anything.

Steps marked ✅ were run against this tree on 2026-08-16. No credentials are needed
and nothing is deployed.

---

## 1. The pipeline runs, start to finish ✅

Four commands. The only network access is `npm install` for a single dependency.

```bash
cd kouryaku && npm install --no-audit --no-fund   # datascript, 1 package
cd ..

nbb --classpath . kouryaku/query.cljs verify
#   レコード 177
#     出典欠落      0
#     revision 欠落 0 (wikidata のみ対象)
#     id 重複       0
#     範囲外 rel    0 種
#   OK

K=<root>/orgs/kotoba-lang            # absolute — see the note below
JP_GO_DDS_CSS=$K/jp-go-digital-design-system/resources/jp_go_dds/dds.css \
nbb --classpath ".:$K/html/src:$K/css/src:$K/jp-go-digital-design-system/src" \
    kouryaku/publish.cljs
#   corpus 177 件 → 公開対象 177 件
#   → site/ に 169 ページ生成
```

**Use an absolute path for `K`.** `kouryaku/README.md` writes `K=../../kotoba-lang`,
which is correct from the west checkout at `orgs/cloud-itonami/media` and wrong from
anywhere else — a worktree under `/tmp` resolves it to `/kotoba-lang`. The three
sibling repositories it needs are `jp-go-digital-design-system`, `html` and `css`.

`kouryaku` carries its own `node_modules` on purpose. Its `package.json` explains
why: the root `package.json` still has `@gftd/magatama-host-sdk` at `workspace:*`
from the monorepo extraction, so a root `npm install` fails with
`EUNSUPPORTEDPROTOCOL`. Do not run `npm install` at the repository root.

169 pages from 177 records, and the layout is worth measuring rather than inferring:

```bash
for d in character item stage; do echo "$d $(find site/$d -name '*.html' | wc -l)"; done
find site -maxdepth 1 -name '*.html' | wc -l
#   character 114     (113 records + that kind's list page)
#   item       41     (40 + 1)
#   stage      13     (12 + 1)
#   top level   1     (index.html)
```

Each kind's list page sits inside its own directory, not at the top. The remaining 12
of the 177 records are `:game` entries and **get no page at all** — which matters in
§3.

## 2. The licence obligation holds on every page ✅

`publish.cljs` states its own duty in its header: it prints the attribution of every
published source in the footer of every page, as the discharge of
`:public-with-attribution`. That is a licence obligation, so it is worth checking
rather than trusting:

```bash
python3 - <<'EOF'
import glob
pages=glob.glob('site/**/*.html',recursive=True)
print("SCANNED\t%d\tkouryaku-pages" % len(pages))
for needle in ('k-footer','このページのデータは以下を出典とする自動生成物です'):
    missing=[p for p in pages if needle not in open(p,encoding='utf-8').read()]
    print(f"  without {needle[:24]!r}: {len(missing)}")
EOF
#   SCANNED	169	kouryaku-pages
#     without 'k-footer': 0
#     without 'このページのデータは以下を出典とする自動生成物です': 0
```

All 169 pages carry the footer, the disclosure sentence, and both attribution names
(`PokéAPI` 169, `Wikidata` 169). Zero exceptions.

Note that every page prints **every** published source, not only the sources that
page draws on — a PokéAPI-only character page still credits Wikidata. The header says
that is the intent ("出した source の attribution を全ページのフッタに必ず刷る"), and
over-attribution is the safe direction, but it does mean the footer is not a
per-page provenance list.

## 3. ✅ The publish gate works — measured by making it refuse

`publish.cljs`'s stated exit gate is `:source/publish?` in `kouryaku/sources.edn`:
not one record from a source with `false` may reach the public surface, decided in
one place so the judgement is not scattered across page builders.

**Both sources are `true`, and there has never been a `false` one.** So "no
forbidden source leaked" was true without the gate ever having to do anything, and
that is not evidence the gate works. Making it refuse takes one edit and one rerun:

```bash
cp kouryaku/sources.edn /tmp/sources-orig.edn
# flip the first :source/publish? true  ->  false   (wikidata)
rm -rf site && …publish.cljs…
#   [gate] 非公開 source のため 12 件を除外: wikidata
#   corpus 177 件 → 公開対象 165 件
#   → site/ に 169 ページ生成
cp /tmp/sources-orig.edn kouryaku/sources.edn        # always restore
```

It refuses, and it says what it refused. Two measured details are worth knowing
before relying on it:

- **the page list does not change.** 169 pages before, 169 after, byte-identical file
  names. Wikidata contributes exactly the 12 `:game` records, and games get no page,
  so excluding them removes data and attribution without removing any page. Do not
  read an unchanged page count as a gate that did nothing.
- **the footer attribution drops correctly** — `Wikidata` falls from 169 pages to 1.
  The one survivor is `site/index.html`, and it is hand-written prose in the
  template, not a registry-derived attribution: *"Wikidata (CC0) 由来。per-title の
  攻略深度は Wikidata には無い…"*. So the gate governs data and attribution, not the
  copy written **about** the sources. Gate a source off and the index still explains
  a catalogue that is no longer published from it.

## 4. The link-medium face is not deployable today

`CLAUDE.md` records that `wrangler deploy --dry-run` passes (3519 KiB, 507 KiB
gzipped, all bindings resolving) and that deployment has deliberately not happened,
because three runtime preconditions are unmet — the first being that the
`media-analysis` Queue does not exist on the account. Treat the build passing as
evidence about the build only.

That is also why `deploy` is not in this quickstart. Nothing here needs credentials.

## 5. Why this document raises `axis-docs` and not `axis-surface`

The maturity tick named `axis-surface` for this repository. It is not honestly
raisable here, and the reason belongs in writing rather than in a ledger only.

`axis-surface` averages four booleans: an HTML file under
`docs|samples|public|demo/`, a cron `.github/workflows/*`, a `business-model` path,
and a `pricing` path.

- **the committed HTML** would mean tracking generator output, and this repository
  has already decided not to, with a reason in `.gitignore`: *"publish.cljs の出力。
  corpus から決定論的に再生成できるので追跡しない"*. Reversing a documented decision
  to move a score is the wrong direction of causation.
- **the cron workflow** is forbidden. ADR-2607300900 makes murakumo fleet the CI/CD
  authority and says not to write new `.github/workflows/*.yml`. The instrument
  rewards what the workspace prohibits; that is the instrument's problem, and the
  answer is not to break the rule for a point.
- **business-model and pricing** would be invented. `kouryaku` publishes a CC0 and
  PokéAPI-derived corpus; there is no pricing to document, and writing speculative
  business claims to move an axis is the padding the loop's own skill forbids.

So the work went into the axis that *is* honestly raisable, and §1–§3 are the result:
a pipeline walked end to end, a licence obligation verified across 169 pages, and a
gate that had never refused anything made to refuse once.

The tick also notes what it cannot see here: `axis-test` reads 0 while
`clj/test/media/` holds two test files, because the instrument counts only a
top-level `test/`; and the README component reads `README.md` while this repository
has `README.md.edn`. Blind spots recorded in ADR-2608052000, not absences.
