package io.github.ajdailey.qlogg.app.ipc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Checks the endpoint file on a file system with ACLs and no POSIX permissions (Windows). The file
 * must be owned by the user, and every allow entry that applies to it must be for a trusted
 * account. Trusted are the user, and the owner of the user's profile directory and every account
 * its allow entries give a right in {@link #WRITE}: on a default profile {@code SYSTEM} and {@code
 * Administrators}, whose names Windows localizes, so they are not looked up by name. The caller
 * passes the profile from {@code USERPROFILE}, not {@code user.home}, which a launcher or test can
 * set to any directory.
 */
final class AclCheck {
  /** Rights that make an account trusted when the profile directory gives it one. */
  static final Set<AclEntryPermission> WRITE =
      EnumSet.of(
          AclEntryPermission.WRITE_DATA,
          AclEntryPermission.APPEND_DATA,
          AclEntryPermission.DELETE,
          AclEntryPermission.DELETE_CHILD,
          AclEntryPermission.WRITE_ACL,
          AclEntryPermission.WRITE_OWNER);

  private AclCheck() {}

  /**
   * Returns the accounts trusted besides the user.
   *
   * @param profile the user's profile directory
   * @return its owner and the accounts its allow entries give a right in {@link #WRITE}
   * @throws IOException when its ACL cannot be read, naming the directory and the exception
   */
  static Set<UserPrincipal> trusted(Path profile) throws IOException {
    try {
      AclFileAttributeView view = Files.getFileAttributeView(profile, AclFileAttributeView.class);
      Set<UserPrincipal> trusted = new HashSet<UserPrincipal>();
      for (AclEntry entry : view.getAcl()) {
        if (applies(entry) && !Collections.disjoint(entry.permissions(), WRITE)) {
          trusted.add(entry.principal());
        }
      }
      trusted.add(view.getOwner());
      return trusted;
    } catch (IOException e) {
      throw new IOException(
          "the ACL of the profile directory " + profile + " cannot be read: " + e, e);
    }
  }

  /**
   * Checks the endpoint file.
   *
   * @param file the endpoint file
   * @param user the current user
   * @param trusted the user and {@link #trusted}
   * @return null when the file is the user's and open to no other account, else the reason
   * @throws IOException when the ACL cannot be read
   */
  static String refusal(Path file, UserPrincipal user, Set<UserPrincipal> trusted)
      throws IOException {
    AclFileAttributeView view =
        Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    UserPrincipal owner = view.getOwner();
    if (!owner.equals(user)) {
      return "the endpoint file belongs to " + owner.getName() + ", not the user";
    }
    for (AclEntry entry : view.getAcl()) {
      if (applies(entry) && !trusted.contains(entry.principal())) {
        return "the endpoint file is open to " + entry.principal().getName();
      }
    }
    return null;
  }

  /** True for an allow entry that applies to the object itself. */
  private static boolean applies(AclEntry entry) {
    return entry.type() == AclEntryType.ALLOW && !entry.flags().contains(AclEntryFlag.INHERIT_ONLY);
  }
}
