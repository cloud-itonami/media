// Type surface for the shadow-cljs :esm output (media.core / media.score /
// media.link). Generated bundle (media.js) by `amu compile --target wasm32-browser worker`.

declare module "../js/media.js" {
  export function validateLink(obj: unknown): { valid: boolean; error?: string };
  export function validateSubject(obj: unknown): { valid: boolean; error?: string };
  export function subjectToTxEdn(subject: unknown): string;
  export function linkToTxEdn(link: unknown): string;
  export function reconcileDeliveryTxEdn(linkId: string, postUri: string, updatedAt: string): string;
  export function deskWriterDid(genre: string): string;
  export function linkToPostText(link: unknown): string;
  export function qListLinks(genre: string | null): string;
  export function qByLinkId(linkId: string): string;
  export function qLinksForSubject(subjectId: string): string;
  export function qListSubjects(kind: string | null): string;
  export function shapeRows(rowsEdn: unknown): any[];
  export function candidateSubjects(obj: unknown): Array<{
    subjectId: string; kind: string; relevance: number; arbitrage: number;
    bridgeScores: Record<string, number>;
  }>;
  export function scoreBridge(obj: unknown): {
    relevance: number; arbitrage: number; bridgeScores: Record<string, number>;
  };
  export function normalizeLang(input: string | null | undefined): string | null;
  export function langDirective(code: string | null | undefined): string;
  export function langName(code: string | null | undefined): string | undefined;
  export function supportedLangs(): string[];
  export function supportedLangCount(): number;
  export function supportedDesks(): string[];
  export function deskProfile(genre: string): { audience: string; tone: string; relation: string } | undefined;
  export function inferRelation(genre: string, text: string): string;
  export function seedSubjects(): Array<{ kind: string; label: string; topic: string; genre: string }>;
}
