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

### Endpoint trust

`EndpointFile.read` reads `instance.endpoint` only when it is a regular file,
read without following links, owned by the current user and open to no other
account:

| File system | Refused when |
|---|---|
| POSIX | Another user owns it, or it has any group or other permission bit |
| ACLs without POSIX (Windows) | `AclCheck`: another account owns it, or an allow entry that applies to it is for an untrusted account |

`AclCheck` trusts the user, and the owner of the profile directory
(`USERPROFILE`) and the accounts its allow entries give write, append, delete,
delete child, write ACL or write owner: `SYSTEM` and `Administrators` on a
default profile. Windows localizes their names, so they are not looked up by
name. `user.home` is not used: the test JVMs set it under `target`.

The attributes are read before the content and again while the file is open,
and must name the same file. Windows opens it without delete sharing, so it
cannot be renamed while open; on POSIX the file key and `ctime` must match,
and a rename sets `ctime`.

An untrusted endpoint, or a step that fails (the user name does not resolve,
`USERPROFILE` is not absolute, an ACL cannot be read), ends the handoff as a
missing endpoint does: the launch opens its own window and the WARN names the
step and the exception.

The settings directory is not checked. Another account that can write it can
delete or rename the endpoint but cannot create a file the user owns. Deleting
the endpoint, or holding `instance.lock`, makes a launch open its own window.

### Lock file guard

`claim` names `instance.lock` in a `SafePaths.HeldLock`, and the launcher puts
`heldLock()` in `DocumentServices`. Every open, import, export and save passes
it to `SafePaths.logPath`, which rejects the lock file with
`INSTANCE_LOCK_REFUSED`. Each server's lock is its own value, so closing one
`InstanceServer` leaves another in the same JVM protected. The POSIX
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
- An interrupt of the accept thread ends it; an interrupt during a held wait
  answers that request `ERR`. Either leaves the interrupt flag set.
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
C: ROTATED 1                            (only with --rotated)
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
- `ROTATED 1` marks every file of the request `FileRequest.rotated`. A primary
  that predates it reads it as a malformed line and answers `ERR`, so the
  launch opens its own window.
- A malformed request (a bad `FOLLOW` or `OPEN` line, more than 256 `OPEN`
  lines) gets `ERR` after the server reads its remaining lines up to `END`: a
  socket closed with unread bytes sends a reset, and on Windows the reset
  discards the `ERR` before the client reads it.
- Every step throws `IOException`, and the launcher then opens its own window.

`LineChannel` runs the socket non-blocking under a `Selector` against one
deadline, because `ArchitectureTest` bans `java.net.Socket`, whose timeouts a
blocking channel would need.
