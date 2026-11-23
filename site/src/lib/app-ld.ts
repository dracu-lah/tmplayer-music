// The home page's structured data (tmplayer.org keeps its SoftwareApplication in the same file name).
//
// WEBSITE is always on the page. SOFTWARE_APP is rendered only while INDEXABLE is true, because it
// describes a released app. Its version, download URL, file size, date and operating system floor
// come from src/data/release.json (the "Release data" section of site/README.md says how that file
// is refreshed), so there is nothing here to edit at a release. Nothing in it may describe a feature
// that is not in the released build. ORGANIZATION is the publisher both point at; the home page
// renders it next to them.
import { ORIGIN } from '../../site.config.mjs';
import release from '../data/release.json';

const GITHUB_USER = 'https://github.com/dracu-lah';

/** The phone shots the home page shows (public/screenshots/<name>.webp, 1080 x 2400). */
const SCREENSHOTS = ['home', 'albums', 'nowplaying', 'lyrics', 'songs'].map((s) => `${ORIGIN}/screenshots/${s}.webp`);

/** Who publishes it: the author's name on GitHub, with the sibling site. */
export const ORGANIZATION = {
  '@context': 'https://schema.org',
  '@type': 'Organization',
  '@id': `${ORIGIN}/#publisher`,
  name: 'dracu-lah',
  url: GITHUB_USER,
  sameAs: [GITHUB_USER, 'https://tmplayer.org/'],
};

// No potentialAction: the site has no search, so no SearchAction is promised.
export const WEBSITE = {
  '@context': 'https://schema.org',
  '@type': 'WebSite',
  '@id': `${ORIGIN}/#website`,
  name: 'TMPlayer Music',
  url: `${ORIGIN}/`,
  inLanguage: 'en',
  publisher: { '@id': `${ORIGIN}/#publisher` },
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
  operatingSystem: `Android ${release.minAndroid} or later, Windows, macOS, Linux`,
  image: `${ORIGIN}/icon-512.png`,
  softwareVersion: release.version,
  datePublished: release.date,
  downloadUrl: release.apk.url,
  installUrl: `${ORIGIN}/download/`,
  fileSize: `${(release.apk.size / (1024 * 1024)).toFixed(1)}MB`,
  releaseNotes: `${ORIGIN}/changelog/${release.tag}/`,
  screenshot: SCREENSHOTS,
  offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
  author: { '@type': 'Person', name: 'dracu-lah', url: GITHUB_USER },
  publisher: { '@id': `${ORIGIN}/#publisher` },
  license: 'https://www.gnu.org/licenses/gpl-3.0.html',
  isPartOf: { '@id': `${ORIGIN}/#website` },
};
