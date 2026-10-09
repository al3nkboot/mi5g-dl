/*
 * Mi5G Dl YouTube sign-in helper (GPL-3.0). Exports youtube.com / google.com cookies of the
 * in-app sign-in session to the app on request. Cookies are never sent anywhere else.
 */
"use strict";

const DOMAINS = ["youtube.com", "google.com"];
let port = null;
let attempts = 0;

async function collectCookies() {
    let stores = [];
    try {
        stores = await browser.cookies.getAllCookieStores();
    } catch (e) {
        stores = [];
    }
    const storeIds = stores.length ? stores.map((s) => s.id) : [undefined];
    if (!storeIds.includes("firefox-private")) storeIds.push("firefox-private");
    const seen = new Set();
    const out = [];
    for (const storeId of storeIds) {
        for (const domain of DOMAINS) {
            let list = [];
            try {
                const query = storeId ? { domain, storeId } : { domain };
                list = await browser.cookies.getAll(query);
            } catch (e) {
                continue;
            }
            for (const c of list) {
                const key = `${c.domain}|${c.path}|${c.name}`;
                if (seen.has(key)) continue;
                seen.add(key);
                out.push({
                    name: c.name, value: c.value, domain: c.domain, path: c.path,
                    secure: !!c.secure, httpOnly: !!c.httpOnly, hostOnly: !!c.hostOnly,
                    session: !!c.session, expirationDate: c.expirationDate || 0
                });
            }
        }
    }
    return out;
}

function connect() {
    attempts += 1;
    try {
        port = browser.runtime.connectNative("mi5gCookies");
    } catch (e) {
        port = null;
    }
    if (!port) {
        if (attempts < 30) setTimeout(connect, 1000);
        return;
    }
    port.onMessage.addListener(async (msg) => {
        if (!msg || typeof msg !== "object") return;
        if (msg.type === "export") {
            try {
                const cookies = await collectCookies();
                port.postMessage({ type: "cookies", id: msg.id || 0, cookies: JSON.stringify(cookies) });
            } catch (e) {
                port.postMessage({ type: "error", id: msg.id || 0, message: String(e && e.message || e) });
            }
        } else if (msg.type === "ping") {
            port.postMessage({ type: "pong", id: msg.id || 0 });
        }
    });
    port.onDisconnect.addListener(() => {
        port = null;
        if (attempts < 30) setTimeout(connect, 1000);
    });
}

connect();
