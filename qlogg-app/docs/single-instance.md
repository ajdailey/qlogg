# Single instance

The single-instance lock and file handoff in `app.ipc` of
[qlogg-app](../README.md): `InstanceServer`, `InstanceClient`, `LineChannel`
and the endpoint file. Where handed files open is in [Windows and
tabs](windows-and-tabs.md#windows).

## Claiming the instance

`InstanceServer.claim(settings)`:

1. takes the OS lock on `instance.lock` with `FileChannel.tryLock`;
2. deletes any `instance.endpoint`;
3. binds an ephemeral loopback port;
4. writes the endpoint (`version=1`, `port`, `token` as 64 hex) owner-only
   through `AtomicFiles.writeOwnerOnly`.

It returns null while another live process holds the lock. The OS releases the
lock when that process dies, SIGKILL included, so a stale endpoint file never
decides liveness.

`refusal` turns the handoff off for a POSIX settings directory another user
owns or others can write.

### Lock file guard

Until `close`, `SafePaths.logPath` rejects the lock file with
`INSTANCE_LOCK_REFUSED`, which every open, import and export shows. The POSIX
lock ends at the close of any channel on the file, the file watch's included,
and a second primary's endpoint is then deleted when the first quits. A
restored session holding the file shows the error once and saves without that
tab.

## Serving requests

`start` accepts on a `qlogg-ipc-` thread and serves each connection on its own,
at most 4 at once; a launch past that waits in the socket backlog of 128.
Authentication and the request are read within 5 s.

Until `deliverTo` names the `Receiver`, an authenticated request stays on its
open connection, unanswered. `deliverTo` does not block. The held requests go
to the receiver in arrival order and each client then reads `OK`, so a client
that exits 0 knows the receiver has its files.

- Held files are bounded by the connection cap: 4 requests of at most 256 files
  and 1 MiB.
- `close`, or 30 s after `claim` without `deliverTo`, answers the held requests
  `ERR`. A primary that dies closes the connections. Either way the client
  opens its own window, so a held file is not lost.
- Past the backlog a connect fails and the launch opens its own window.

`close` stops accepting, deletes the endpoint, then releases the lock. Call it
before `Workers.close`, which waits for the accept thread.

## Handing off

`InstanceClient.handOff` reads the endpoint, waiting up to 2 s while it is
missing, connects within 1 s and runs the exchange within 35 s. The 35 s bound
outlasts the 30 s hold, so the client reads the `ERR`.

```text
C: QLOGG 1 HELLO nonceC
S: QLOGG 1 PROOF nonceS HMAC(token, "server" nonceC nonceS)
C: AUTH HMAC(token, "client" nonceS nonceC)
C: FOLLOW 0|1
C: OPEN base64url(UTF-8 path) line      (repeated, at most 256; line 0 = none)
C: END
S: OK | ERR
```

- Nonces are 32 bytes from `SecureRandom`; proofs are compared with
  `MessageDigest.isEqual`.
- The client sends nothing after a wrong `PROOF`, so no file reaches a listener
  without the token. The server sends nothing after a wrong `AUTH`.
- Lines are printable ASCII up to 16 KiB, at most 1 MiB per connection. Each
  path must be absolute.
- Every step throws `IOException`, and the launcher then opens its own window.

`LineChannel` runs the socket non-blocking under a `Selector` against one
deadline, because `ArchitectureTest` bans `java.net.Socket`, whose timeouts a
blocking channel would need.
