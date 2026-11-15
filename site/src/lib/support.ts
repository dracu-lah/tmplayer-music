// Where the site's support links go, the same pages as tmplayer.org's (its src/lib/support.ts), the
// app's About screen and .github/FUNDING.yml. One place, so the home page, the footer and the
// support page cannot drift apart. nevil.dev is the right Buy Me a Coffee page
// (buymeacoffee.com/nevil is somebody else's).
export const BMC_URL = 'https://buymeacoffee.com/nevil.dev';
export const SPONSORS_URL = 'https://github.com/sponsors/dracu-lah';
export const REPO_URL = 'https://github.com/dracu-lah/tmplayer-music';

// Pay by UPI (India): the same ID, link and QR as tmplayer.org's src/lib/support.ts, since both
// sites are one author's. The link is what a UPI app reads and what /brand/upi-qr.svg encodes. On
// a phone it opens the app; a computer gets the QR and the ID to copy (UpiDialog).
export const UPI_ID = 'nevilnicks4321@okaxis';
export const UPI_LINK = `upi://pay?pa=${UPI_ID}&pn=TMPlayer&cu=INR&tn=${encodeURIComponent('TMPlayer Music support')}`;
export const UPI_QR = '/brand/upi-qr.svg';

export const RELEASES_URL = `${REPO_URL}/releases`;
export const LATEST_URL = `${REPO_URL}/releases/latest`;
export const ISSUES_URL = `${REPO_URL}/issues`;
export const SECURITY_URL = `${REPO_URL}/security/advisories/new`;
