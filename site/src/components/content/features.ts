// The Features page's groups and cards as data: each group has an icon per card, a heading, an intro and
// cards, and most a phone screenshot. Every string is a catalog key under features.*, resolved where it
// is drawn. Only what the app really does is listed here (checked against the app's own strings and
// README.md).
//
// A card's icon is a name from Icon.astro.
export type IconName = 'music' | 'layers' | 'play' | 'lyrics' | 'list' | 'disk' | 'search' | 'lock' | 'timer' | 'shield' | 'check' | 'heart' | 'download';

export interface FeatureCard { icon: IconName; h: string; p: string }
export interface FeatureGroup {
  id: string;
  h: string;
  intro: string;
  /** a screenshot beside the cards: the file in public/screenshots and the key of its alt text */
  shot?: { screen: string; alt: string };
  cards: FeatureCard[];
}

export const GROUPS: FeatureGroup[] = [
  {
    id: 'library', h: 'features.library_h', intro: 'features.library_intro',
    shot: { screen: 'songs', alt: 'features.shot_songs_alt' },
    cards: [
      { icon: 'music', h: 'features.lib_chats_h', p: 'features.lib_chats_p' },
      { icon: 'list', h: 'features.lib_lists_h', p: 'features.lib_lists_p' },
      { icon: 'search', h: 'features.lib_search_h', p: 'features.lib_search_p' },
      { icon: 'layers', h: 'features.lib_versions_h', p: 'features.lib_versions_p' },
      { icon: 'disk', h: 'features.lib_albums_h', p: 'features.lib_albums_p' },
    ],
  },
  {
    id: 'playback', h: 'features.playback_h', intro: 'features.playback_intro',
    shot: { screen: 'nowplaying', alt: 'features.shot_nowplaying_alt' },
    cards: [
      { icon: 'play', h: 'features.play_stream_h', p: 'features.play_stream_p' },
      { icon: 'music', h: 'features.play_background_h', p: 'features.play_background_p' },
      { icon: 'list', h: 'features.play_queue_h', p: 'features.play_queue_p' },
      { icon: 'check', h: 'features.play_nothing_h', p: 'features.play_nothing_p' },
      { icon: 'layers', h: 'features.play_sound_h', p: 'features.play_sound_p' },
    ],
  },
  {
    id: 'lyrics', h: 'features.lyrics_h', intro: 'features.lyrics_intro',
    shot: { screen: 'lyrics', alt: 'features.shot_lyrics_alt' },
    cards: [
      { icon: 'lyrics', h: 'features.lyr_synced_h', p: 'features.lyr_synced_p' },
      { icon: 'timer', h: 'features.lyr_timing_h', p: 'features.lyr_timing_p' },
      { icon: 'shield', h: 'features.lyr_lookup_h', p: 'features.lyr_lookup_p' },
    ],
  },
  {
    id: 'data', h: 'features.data_h', intro: 'features.data_intro',
    shot: { screen: 'account', alt: 'features.shot_account_alt' },
    cards: [
      { icon: 'disk', h: 'features.data_cache_h', p: 'features.data_cache_p' },
      { icon: 'check', h: 'features.data_free_h', p: 'features.data_free_p' },
      { icon: 'download', h: 'features.data_offline_h', p: 'features.data_offline_p' },
      { icon: 'shield', h: 'features.data_backup_h', p: 'features.data_backup_p' },
      { icon: 'lock', h: 'features.data_remove_h', p: 'features.data_remove_p' },
    ],
  },
  {
    id: 'playlists', h: 'features.playlists_h', intro: 'features.playlists_intro',
    shot: { screen: 'playlists', alt: 'features.shot_playlists_alt' },
    cards: [
      { icon: 'heart', h: 'features.pl_fav_h', p: 'features.pl_fav_p' },
      { icon: 'list', h: 'features.pl_lists_h', p: 'features.pl_lists_p' },
      { icon: 'play', h: 'features.pl_home_h', p: 'features.pl_home_p' },
    ],
  },
  {
    id: 'sleep', h: 'features.sleep_h', intro: 'features.sleep_intro',
    cards: [
      { icon: 'timer', h: 'features.sleep_minutes_h', p: 'features.sleep_minutes_p' },
      { icon: 'check', h: 'features.sleep_end_h', p: 'features.sleep_end_p' },
    ],
  },
];

/** The "What it does not do" list. */
export const NOT_DO: string[] = ['features.not1', 'features.not2', 'features.not3', 'features.not4', 'features.not5', 'features.not6'];
