# Voodoo Browser Bridge relay

A single-owner, single-process relay for Voodoo's outbound WebSocket connection
and an OAuth-protected Streamable HTTP MCP endpoint. It uses the official MCP
SDK. No OpenAI API key or model calls are needed to run the relay.

## Setup

Requires Node 22 or later and an HTTPS reverse proxy supporting WebSocket
upgrades. Keep the process bound to loopback. Do not expose its plain HTTP port
on the Internet. Run one instance per durable state file; horizontal replication
and multiple independent owners are not supported.

```sh
cd bridge-server
npm ci
npm test
cp .env.example .env
```

Fill `.env` with your HTTPS origin, an OAuth client ID, the exact callback URL
shown by your MCP client, and three distinct random secrets. Generate each
secret separately, for example with `openssl rand -hex 32`:

- `BRIDGE_ADMIN_SECRET`: only for the operator's pairing/revocation requests.
- `BRIDGE_OWNER_SECRET`: the password entered on this relay's consent page.
- `BRIDGE_OAUTH_CLIENT_SECRET`: configured with the client ID in ChatGPT's
  custom MCP connection. This is not an OpenAI API key.

Start with `node --env-file=.env src/server.mjs`. Production supervisors can
supply environment variables directly and use `npm start`. Protect the env file,
back up the state file securely, and never log Authorization headers or bodies.
The relay holds hashed credentials and forwards page content/screenshots. A
compromised relay can control allowed sites while the phone bridge is enabled;
it must be hosted somewhere you trust.

## Pair the phone

From the operator environment where the admin secret is available:

```sh
curl --fail-with-body -X POST https://bridge.example.com/pair/start \
  -H "Authorization: Bearer $BRIDGE_ADMIN_SECRET"
```

Enter the returned 16-character, single-use code within five minutes in Voodoo:
Tools → ChatGPT Browser Bridge → Pair device. Then add allowed websites, resume
the bridge, and return to the browser. All sites are blocked by default.
Website credentials remain in the WebView; the bridge does not export cookies.
Read page text and screenshots can still contain private website information.

## Connect an MCP client

Configure `https://bridge.example.com/mcp` with OAuth and the pre-registered
client ID/secret. The authorization page asks for the relay owner password and
explains browser access. PKCE S256 and the exact `/mcp` resource are required.
Dynamic client registration is deliberately not offered. ChatGPT connection
availability depends on the account/client; a mobile UI that cannot invoke this
custom connection cannot be fixed by the Android app alone.

Metadata is at `/.well-known/oauth-protected-resource/mcp` and
`/.well-known/oauth-authorization-server`. Access tokens last one hour; refresh
tokens rotate and last up to 30 days. `/revoke` revokes the current token grant.
One owner can pair up to 20 devices. With multiple devices, tools require an
explicit `deviceId`; use `browser_list_devices` first.

## Revocation and failure handling

The phone's Revoke pairing button disables local work immediately, calls
`/device/revoke`, and removes the saved token after relay acknowledgement. If
offline, the UI says revocation is incomplete and offers retry by pressing the
same button. For a lost phone, an operator can POST `{"deviceId":"dev-..."}` to
`/admin/device/revoke` using the admin bearer token.

Commands are serialized on the phone and expire within 30 seconds. The relay
never automatically retries a browser action. Disconnects/timeouts report an
uncertain outcome: inspect task status and the page before repeating any write.
The phone rejects duplicate command IDs using its bounded, durable journal;
this does not deduplicate two separate tool calls with different IDs. Pause and
cancel stop queued work; they cannot undo an already dispatched website event.
No page text, typed content, or screenshots are persisted in the relay state.

## Tests and limits

`npm test` runs real HTTP/OAuth/MCP/WebSocket integration tests with simulated
phones, including PKCE, code replay, rotation/revocation, strict tool validation,
response ownership, reconnect races, timeouts, and oversized requests. These
are not a phone or ChatGPT end-to-end acceptance test. See
[bridge review](../docs/BRIDGE-REVIEW.md) for the exact verification status.
