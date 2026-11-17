// Hands the app's YouTube session to this browser profile, and takes back the
// one a sign-in can leave in it.
//
// GeckoView's Java API has no cookie accessor, so the only surface that can
// write a cookie into the engine is one of its own extensions. This one holds
// no policy of its own: it replaces the profile's YouTube and Google cookies
// with exactly the set the app sends, and reports back how many it removed and
// stored.
//
// Values only ever travel app -> engine. Nothing here reads a cookie value out
// of the profile, and the replies carry names and counts so the app can log
// what happened without printing a session.

// The application name is handed to browser.runtime.connectNative, whose schema
// only accepts word characters and dots, so it cannot be hyphenated.
const NATIVE_APP = "wetube.session";
const ORIGIN = "https://www.youtube.com/";
const DOMAIN = ".youtube.com";

// Every site a session handed to this engine can live on. Google is here
// because the engine admits accounts.google.com: a sign-in there sets a
// `.google.com` cookie the app neither made nor holds, and leaving it behind
// would let the next watch page answer as that Google account.
const SESSION_DOMAINS = ["youtube.com", "google.com"];

let port = null;

function connect() {
  let connected = null;
  try {
    connected = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    // The app registers its native side right after installing this extension,
    // so the first attempt can lose the race; keep trying, but say why.
    console.error("wetube.session: connectNative threw:", e && e.message);
    connected = null;
  }
  if (!connected) {
    // The app registers its native side right after installing this
    // extension, so the first attempt can lose the race.
    setTimeout(connect, 250);
    return;
  }
  console.log("wetube.session: port open");
  port = connected;
  port.onMessage.addListener(onMessage);
  port.onDisconnect.addListener(() => {
    if (port === connected) {
      port = null;
    }
    setTimeout(connect, 250);
  });
  port.postMessage({ type: "ready" });
}

// Drops every cookie this extension can see for a site a session lives on.
//
// Addressed one cookie at a time by its own domain and path: the profile holds
// cookies for www.youtube.com, m.youtube.com and .google.com alike, and a
// removal sent to the wrong host leaves the old session in place.
async function clearSessionCookies() {
  let removed = 0;
  for (const domain of SESSION_DOMAINS) {
    const existing = await browser.cookies.getAll({ domain });
    for (const cookie of existing) {
      const gone = await browser.cookies.remove(addressOf(cookie));
      if (gone) {
        removed++;
      }
    }
  }
  return removed;
}

// Replaces every YouTube and Google cookie in this profile with `cookies`.
//
// Clearing first is what makes an account switch land: a leftover session
// cookie would otherwise outlive the switch and the watch page would keep
// answering as the account that was just replaced. Only the app's own jar is
// written back, so anything the page signed in as is gone by the time the next
// page loads.
async function seed(cookies) {
  const removed = await clearSessionCookies();

  const failed = [];
  for (const cookie of cookies) {
    const detail = {
      url: ORIGIN,
      domain: DOMAIN,
      path: "/",
      name: cookie.name,
      value: cookie.value,
      secure: true,
      httpOnly: cookie.httpOnly,
      sameSite: cookie.sameSite,
      // Deliberately no expirationDate: a session cookie lives only as long as
      // the runtime, so the app's own copy of the account is never written to
      // the profile's cookie database. What the page signs itself in as is a
      // different matter — that is what clearSessionCookies() is for — and the
      // app clears the whole profile when its session ends.
    };
    try {
      const stored = await browser.cookies.set(detail);
      if (!stored) {
        console.error("wetube.session: set returned nothing for", cookie.name);
        failed.push(cookie.name);
      }
    } catch (e) {
      // Names and the engine's complaint only — never the value.
      console.error(
        "wetube.session: set failed for",
        cookie.name,
        "httpOnly=" + cookie.httpOnly,
        "sameSite=" + cookie.sameSite,
        String((e && e.message) || e)
      );
      failed.push(cookie.name);
    }
  }
  return { removed, set: cookies.length - failed.length, failed };
}

// The url that addresses one specific cookie: its own host, its own path.
function addressOf(cookie) {
  const host = (cookie.domain || "").replace(/^\./, "");
  const path = cookie.path || "/";
  const scheme = cookie.secure ? "https" : "http";
  return { url: scheme + "://" + host + path, name: cookie.name };
}

function onMessage(message) {
  if (!message || message.type !== "seed") {
    return;
  }
  const id = message.id;
  seed(message.cookies || []).then(
    (result) => {
      if (port) {
        port.postMessage({ type: "seeded", id, ...result });
      }
    },
    (error) => {
      if (port) {
        port.postMessage({
          type: "seeded",
          id,
          removed: 0,
          set: 0,
          failed: [],
          error: String(error),
        });
      }
    }
  );
}

connect();