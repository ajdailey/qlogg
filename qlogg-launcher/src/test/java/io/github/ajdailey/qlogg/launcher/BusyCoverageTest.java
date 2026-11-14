package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every task {@code qlogg-app} submits to {@link Workers}, listed with what the user sees while it
 * runs. A site is the named method that submits a task directly or creates an anonymous {@code
 * BusyWork}, after leaving anonymous and local classes. The test fails when a site is missing from
 * {@link #SITES} or listed but gone, and when a site listed as showing the busy cursor neither
 * creates a {@code BusyWork} nor makes the call its row names.
 *
 * <p>Indexing and the search run on threads {@code qlogg-core} starts, outside this list; their
 * controllers pair {@code setActive} and {@code setIdle} by hand.
 */
class BusyCoverageTest {
  private static final String APP = "io.github.ajdailey.qlogg.app.";
  private static final String BUSY_WORK = APP + "controller.BusyWork";

  /** What the user sees while a site's task runs. */
  private enum Shows {
    /** The window's busy cursor: a {@code BusyWork}, or the site makes its row's call. */
    BUSY,
    /** User-started, but bounded by a small input; no cursor. */
    QUICK,
    /** Background upkeep the user did not start; no cursor. */
    UPKEEP
  }

  /**
   * One site.
   *
   * @param shows what the user sees
   * @param call the call that raises the cursor when the site creates no {@code BusyWork}; null for
   *     none
   */
  private record Site(Shows shows, String call) {}

  private static final Map<String, Site> SITES = new TreeMap<String, Site>();

  static {
    String[] wrapped = {
      "controller.DocumentController#toggleMarks",
      "controller.LineViewController#copy",
      "controller.QuickFindController#find",
      "controller.SearchController#selectRange",
      "controller.TimeController#start"
    };
    for (String site : wrapped) {
      row(site, Shows.BUSY, null);
    }
    row("controller.BusyWork#submit", Shows.BUSY, "setActive");
    // User-started with a small input: one row, one line's time, a file's attributes, the About
    // facts, a filter or highlight file, a scratchpad text under its transform limit, or a file
    // open whose indexing then shows the cursor.
    String[] quick = {
      "controller.ApplicationController#checkDragged",
      "controller.ApplicationController#cleanRecent",
      "controller.ApplicationController#runFilterFile",
      "controller.ApplicationController#runHighlightFile",
      "controller.ApplicationController#transform",
      "controller.DocumentOpener#open",
      "controller.DocumentOpener#paste",
      "controller.DocumentOpener#reopen",
      "controller.HelpController#start",
      "controller.LineViewController#selectedText",
      "controller.PathCommands#openInEditor",
      "controller.RecentFiles#openAll",
      "controller.TimeController#readCaretTime",
      "controller.TimeFeatures#editFormats",
      "controller.ViewFeatures#save"
    };
    for (String site : quick) {
      row(site, Shows.QUICK, null);
    }
    String[] upkeep = {
      "controller.ApplicationController#closeOnWorker",
      "controller.ApplicationController#loadEncodingChoices",
      "controller.CaretTimes#request",
      "controller.DocumentController#close",
      "controller.DocumentOpener#restore",
      "controller.FilteredController#buildContext",
      "controller.FilteredController#buildExtras",
      "controller.FoldController#classify",
      "controller.LineViewController#requestLines",
      "controller.MarksListController#refresh",
      "controller.OverviewController#refresh",
      "controller.TriggerController#start",
      "controller.ViewFeatures#chooseHighlightSet",
      "controller.WindowSet#close",
      "controller.WindowSet#flushSettings",
      "controller.WindowSet#quit",
      "ipc.InstanceServer#accept",
      "ipc.InstanceServer#start",
      "log.EventLoopWatchdog#start"
    };
    for (String site : upkeep) {
      row(site, Shows.UPKEEP, null);
    }
  }

  private static void row(String site, Shows shows, String call) {
    SITES.put(site, new Site(shows, call));
  }

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(
                new ImportOption() {
                  @Override
                  public boolean includes(Location location) {
                    return !location.contains("-tests.jar");
                  }
                })
            .importPackages(APP.substring(0, APP.length() - 1));
  }

  @Test
  void everyWorkerSiteIsListed() {
    Map<String, Boolean> found = sites();
    assertEquals(new TreeSet<String>(SITES.keySet()), new TreeSet<String>(found.keySet()));
  }

  @Test
  void listedOperationsShowTheirActivity() {
    Map<String, Boolean> found = sites();
    List<String> silent = new ArrayList<String>();
    for (Map.Entry<String, Site> e : SITES.entrySet()) {
      Site site = e.getValue();
      if (site.shows() != Shows.BUSY) {
        continue;
      }
      boolean wrapped = Boolean.TRUE.equals(found.get(e.getKey()));
      if (!wrapped && (site.call() == null || !calls(e.getKey(), site.call()))) {
        silent.add(e.getKey());
      }
    }
    assertTrue(silent.isEmpty(), "show no activity: " + silent);
  }

  /** The call check fails on a method that does not make the call. */
  @Test
  void theCallCheckFindsAMissingCall() {
    assertTrue(calls("controller.BusyWork#submit", "setActive"));
    assertFalse(calls("controller.CaretTimes#request", "setActive"));
  }

  /** Maps each site to true when it creates an anonymous {@code BusyWork}. */
  private static Map<String, Boolean> sites() {
    Map<String, Boolean> found = new TreeMap<String, Boolean>();
    for (JavaClass type : classes) {
      for (JavaCall<?> call : type.getCodeUnitCallsFromSelf()) {
        JavaClass target = call.getTargetOwner();
        String name = call.getTarget().getName();
        boolean submits =
            target.isEquivalentTo(Workers.class) && name.matches("tryStart|start|executeCpu");
        boolean wraps =
            name.equals("<init>") && target.isAnonymousClass() && target.isAssignableTo(BUSY_WORK);
        if (submits || wraps) {
          String key = key(call.getOrigin());
          found.put(key, wraps || Boolean.TRUE.equals(found.get(key)));
        }
      }
    }
    return found;
  }

  /** Names the named method that holds code, leaving anonymous and local classes. */
  private static String key(JavaCodeUnit code) {
    JavaCodeUnit named = code;
    while (named.getOwner().isAnonymousClass() || named.getOwner().isLocalClass()) {
      Optional<JavaCodeUnit> enclosing = named.getOwner().getEnclosingCodeUnit();
      if (enclosing.isEmpty()) {
        break;
      }
      named = enclosing.get();
    }
    return named.getOwner().getName().substring(APP.length()) + "#" + named.getName();
  }

  /**
   * Reports whether a method, or an anonymous class inside it, calls a method or reads a field of
   * that name.
   */
  private static boolean calls(String method, String call) {
    for (JavaClass type : classes) {
      for (JavaAccess<?> c : type.getAccessesFromSelf()) {
        if (c.getTarget().getName().equals(call) && key(c.getOrigin()).equals(method)) {
          return true;
        }
      }
    }
    return false;
  }
}
