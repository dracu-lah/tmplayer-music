// The home page's structured data (tmplayer.org keeps its SoftwareApplication in the same file name).
//
// WEBSITE is always on the page. SOFTWARE_APP is rendered only once INDEXABLE is true, because it
// describes a released app: before flipping the switch, check every field against the release
// (version, download URL, operating system floor, screenshot) as the "Going live" checklist in
// site/README.md says. Nothing in it may describe a feature that is not in the released build.
import { ORIGIN } from '../../site.config.mjs';

export const WEBSITE = {
  '@context': 'https://schema.org',
  '@type': 'WebSite',
  '@id': `${ORIGIN}/#website`,
  name: 'TMPlayer Music',
  url: `${ORIGIN}/`,
  inLanguage: 'en',
};

export const SOFTWARE_APP = {
  '@context': 'https://schema.org',
  '@type': 'SoftwareApplication',
  '@id': `${ORIGIN}/#app`,
  name: 'TMPlayer Music',
  description:
    'TMPlayer Music is a player for the audio files already in your own Telegram chats. Unofficial, not affiliated with Telegram.',
  url: `${ORIGIN}/`,
  applicationCategory: 'MultimediaApplication',
  operatingSystem: 'Android 6.0 or later',
  image: `${ORIGIN}/icon-512.png`,
  softwareVersion: '0.1.0',
  downloadUrl: 'https://github.com/dracu-lah/tmplayer-music/releases/latest',
  screenshot: `${ORIGIN}/screenshots/home.webp`,
  offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
  author: { '@type': 'Person', name: 'dracu-lah', url: 'https://github.com/dracu-lah' },
  license: 'https://www.gnu.org/licenses/gpl-3.0.html',
  isPartOf: { '@id': `${ORIGIN}/#website` },
};
