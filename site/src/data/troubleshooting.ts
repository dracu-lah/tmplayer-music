// The troubleshooting guides as data: a slug (the path under /troubleshooting/), a key prefix in the
// catalog and how many causes and steps the guide has. The strings stay in the catalog (trouble.*):
//
//   <prefix>_title   the <title>            <prefix>_h1    the heading         <prefix>_desc  the meta description
//   <prefix>_card    the line on the index  <prefix>_sym   what you see
//   <prefix>_cause1.. the likely causes     <prefix>_s1_h, <prefix>_s1_p ..  the steps
//   <prefix>_logs    when to send logs
//
// i18n-keys: trouble.blocked_card, trouble.blocked_cause1, trouble.blocked_cause2, trouble.blocked_cause3, trouble.blocked_desc, trouble.blocked_h1, trouble.blocked_logs, trouble.blocked_s1_h, trouble.blocked_s1_p, trouble.blocked_s2_h, trouble.blocked_s2_p, trouble.blocked_s3_h, trouble.blocked_s3_p, trouble.blocked_s4_h, trouble.blocked_s4_p, trouble.blocked_sym, trouble.blocked_title, trouble.lyrics_card, trouble.lyrics_cause1, trouble.lyrics_cause2, trouble.lyrics_cause3, trouble.lyrics_cause4, trouble.lyrics_cause5, trouble.lyrics_desc, trouble.lyrics_h1, trouble.lyrics_logs, trouble.lyrics_s1_h, trouble.lyrics_s1_p, trouble.lyrics_s2_h, trouble.lyrics_s2_p, trouble.lyrics_s3_h, trouble.lyrics_s3_p, trouble.lyrics_s4_h, trouble.lyrics_s4_p, trouble.lyrics_sym, trouble.lyrics_title, trouble.noplay_card, trouble.noplay_cause1, trouble.noplay_cause2, trouble.noplay_cause3, trouble.noplay_cause4, trouble.noplay_desc, trouble.noplay_h1, trouble.noplay_logs, trouble.noplay_s1_h, trouble.noplay_s1_p, trouble.noplay_s2_h, trouble.noplay_s2_p, trouble.noplay_s3_h, trouble.noplay_s3_p, trouble.noplay_s4_h, trouble.noplay_s4_p, trouble.noplay_sym, trouble.noplay_title, trouble.notracks_card, trouble.notracks_cause1, trouble.notracks_cause2, trouble.notracks_cause3, trouble.notracks_cause4, trouble.notracks_cause5, trouble.notracks_cause6, trouble.notracks_desc, trouble.notracks_h1, trouble.notracks_logs, trouble.notracks_s1_h, trouble.notracks_s1_p, trouble.notracks_s2_h, trouble.notracks_s2_p, trouble.notracks_s3_h, trouble.notracks_s3_p, trouble.notracks_s4_h, trouble.notracks_s4_p, trouble.notracks_s5_h, trouble.notracks_s5_p, trouble.notracks_sym, trouble.notracks_title, trouble.storage_card, trouble.storage_cause1, trouble.storage_cause2, trouble.storage_cause3, trouble.storage_desc, trouble.storage_h1, trouble.storage_logs, trouble.storage_s1_h, trouble.storage_s1_p, trouble.storage_s2_h, trouble.storage_s2_p, trouble.storage_s3_h, trouble.storage_s3_p, trouble.storage_s4_h, trouble.storage_s4_p, trouble.storage_s5_h, trouble.storage_s5_p, trouble.storage_sym, trouble.storage_title
import type { IconName } from '../components/content/features';

export interface Guide {
  slug: string;
  prefix: string;
  icon: IconName;
  causes: number;
  steps: number;
}

export const GUIDES: Guide[] = [
  { slug: 'telegram-blocked', prefix: 'trouble.blocked', icon: 'lock', causes: 3, steps: 4 },
  { slug: 'no-tracks-found', prefix: 'trouble.notracks', icon: 'search', causes: 6, steps: 5 },
  { slug: 'track-will-not-play', prefix: 'trouble.noplay', icon: 'play', causes: 4, steps: 4 },
  { slug: 'storage-full', prefix: 'trouble.storage', icon: 'disk', causes: 3, steps: 5 },
  { slug: 'lyrics-missing', prefix: 'trouble.lyrics', icon: 'lyrics', causes: 5, steps: 4 },
];

const range = (n: number) => Array.from({ length: n }, (_, i) => i + 1);

/** The keys of the guide's causes. */
export const causeKeys = (g: Guide) => range(g.causes).map((n) => `${g.prefix}_cause${n}`);
/** The keys of the guide's steps: heading and text. */
export const stepKeys = (g: Guide) => range(g.steps).map((n) => ({ h: `${g.prefix}_s${n}_h`, p: `${g.prefix}_s${n}_p` }));
