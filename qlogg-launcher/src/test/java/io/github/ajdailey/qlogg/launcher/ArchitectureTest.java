package io.github.ajdailey.qlogg.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.github.ajdailey.qlogg.app.about.UserGuide;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.PositionStore;
import io.github.ajdailey.qlogg.app.config.SessionStore;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.WorkerThread;
import io.github.ajdailey.qlogg.app.highlight.HighlightFile;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.search.FilterFile;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.AtomicFiles;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.FoldClassifier;
import io.github.ajdailey.qlogg.core.model.FoldMap;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.LineFinder;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.Viewport;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.RangeFinder;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.text.Charsets;
import io.github.ajdailey.qlogg.core.time.TimeReader;
import io.github.ajdailey.qlogg.core.time.TimeSamples;
import io.github.ajdailey.qlogg.core.time.TimeSearch;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import java.beans.XMLDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.AccessibleObject;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.Timer;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Item;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Module, security and thread-ownership rules over every class except the bootstrap, which has its
 * own test.
 */
class ArchitectureTest {
  private static final String ROOT = "io.github.ajdailey.qlogg";
  private static final String CORE = ROOT + ".core..";
  private static final String APP = ROOT + ".app..";
  private static final String UI = ROOT + ".ui..";
  private static final String LAUNCHER = ROOT + ".launcher..";
  private static final String[] VIEWS = {ROOT + ".ui.swt..", ROOT + ".app.view.."};

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
            .importPackages(ROOT);
  }

  @Test
  void swtOnlyInViewsAndLauncher() {
    noClasses()
        .that()
        .resideOutsideOfPackages(ROOT + ".ui.swt..", LAUNCHER)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.eclipse.swt..")
        .check(classes);
  }

  /**
   * Only SWT's public API is used, so the same code runs on GTK3 and GTK4 and on every platform's
   * SWT build. The internal namespace holds the backend bindings.
   */
  @Test
  void noSwtInternals() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.eclipse.swt.internal..")
        .check(classes);
  }

  // Public SWT API exceptions: Always on Top, GTK 4 tool item sizing, win32 dark combo list
  // selection, win32 menu themes and the foreground grant.

  /** The classes that reach SWT internals by reflection, which no import shows. */
  private static final String[] SWT_REFLECTION = {
    ROOT + ".ui.swt.platform.Win32Stacking",
    ROOT + ".ui.swt.platform.CocoaStacking",
    ROOT + ".ui.swt.platform.GtkX11Stacking",
    ROOT + ".ui.swt.platform.Gtk4ToolItemSizing",
    ROOT + ".ui.swt.platform.Win32ComboListSelection"
  };

  /** The view class excepted to link the Java 21 foreign-function API by reflection. */
  private static final String VIEW_FOREIGN = ROOT + ".ui.swt.platform.Win32MenuThemes";

  /** The core class excepted to link the Java 21 foreign-function API by reflection. */
  private static final String FOREGROUND_FOREIGN = ROOT + ".core.util.os.WindowsForegroundRights";

  /**
   * Only the listed exception classes, {@code Win32MenuThemes} and {@code WindowsForegroundRights},
   * which link the Java 21 foreign-function API, load classes by name or open members.
   */
  @Test
  void reflectionOnlyInExceptionClasses() {
    reflectionOnlyInExceptionClassesRule().check(classes);
  }

  private static ArchRule reflectionOnlyInExceptionClassesRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(SWT_REFLECTION[0])
        .and()
        .doNotHaveFullyQualifiedName(SWT_REFLECTION[1])
        .and()
        .doNotHaveFullyQualifiedName(SWT_REFLECTION[2])
        .and()
        .doNotHaveFullyQualifiedName(SWT_REFLECTION[3])
        .and()
        .doNotHaveFullyQualifiedName(SWT_REFLECTION[4])
        .and()
        .doNotHaveFullyQualifiedName(VIEW_FOREIGN)
        .and()
        .doNotHaveFullyQualifiedName(FOREGROUND_FOREIGN)
        .should()
        .callMethodWhere(call(Class.class, "forName|getDeclared.*|getField|getMethod"))
        .orShould()
        .callMethodWhere(call(AccessibleObject.class, "setAccessible|trySetAccessible"))
        .because(
            "SWT internals and native calls differ per platform; only these classes reach them");
  }

  // Platform traits.

  /**
   * Only {@code ui.swt.platform} reads {@code SWT.getPlatform()}, names {@code SWT_GTK4} or an
   * {@code org.eclipse.swt.internal} property or class, or, among view and launcher classes, reads
   * the environment, where SWT takes {@code SWT_GTK4}.
   */
  @Test
  void platformOnlyInTraits() {
    platformOnlyInTraitsRule().check(classes);
  }

  @Test
  void platformRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        platformOnlyInTraitsRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$ReadsPlatform",
        "SWT.getPlatform",
        "System.getenv",
        "ReadsPlatform holds a string starting \"org.eclipse.swt.internal.\"",
        "ReadsPlatform holds \"SWT_GTK4\"");
  }

  private static ArchRule platformOnlyInTraitsRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".ui.swt.platform..")
        .should()
        .callMethodWhere(call(SWT.class, "getPlatform"))
        .orShould()
        .callMethodWhere(
            new DescribedPredicate<JavaMethodCall>("call System.getenv in ui.swt or launcher") {
              @Override
              public boolean test(JavaMethodCall call) {
                return call.getTarget().getName().equals("getenv")
                    && call.getTarget().getOwner().isEquivalentTo(System.class)
                    && call.getOriginOwner()
                        .getPackageName()
                        .matches(".*\\.(ui\\.swt|launcher)(\\..*)?");
              }
            })
        .orShould(holdAStringStarting("org.eclipse.swt.internal."))
        .orShould(holdTheString("SWT_GTK4"))
        .because(
            "view-layer platform code stays in ui.swt.platform, behind a seam PlatformTraits chooses");
  }

  /**
   * The {@code PlatformTraits} and {@code OsTraits} booleans a class outside the platform packages
   * may read: values passed on as data. Every other platform behavior is a seam method.
   */
  private static final String[] VALUE_TRAITS = {
    "macKeys",
    "macKeymap",
    "takesCtrlShiftZero",
    "runsInsteadOfOpening",
    "keepFileClosedByDefault",
    "watchServiceByDefault"
  };

  /** Outside the platform packages no class branches on a boolean trait other than a value one. */
  @Test
  void behaviorOnlyThroughSeams() {
    behaviorOnlyThroughSeamsRule(VALUE_TRAITS).check(classes);
  }

  @Test
  void seamRuleFailsOnItsFixtures() throws ClassNotFoundException {
    assertViolations(
        behaviorOnlyThroughSeamsRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$SwitchesOnATrait",
        "PlatformTraits.macKeys()");
    assertViolations(
        behaviorOnlyThroughSeamsRule(),
        ROOT + ".app.controller.archfixture.ControllerViolations$AsksTheOs",
        "OsTraits.macKeymap()");
  }

  private static ArchRule behaviorOnlyThroughSeamsRule(String... values) {
    final List<String> allowed = List.of(values);
    return noClasses()
        .that()
        .resideOutsideOfPackages(ROOT + ".ui.swt.platform..", ROOT + ".core.util.os..")
        .should()
        .callMethodWhere(
            new DescribedPredicate<JavaMethodCall>(
                "call a boolean PlatformTraits or OsTraits method that is not a value trait") {
              @Override
              public boolean test(JavaMethodCall call) {
                JavaClass owner = call.getTarget().getOwner();
                return (owner.isAssignableTo(PlatformTraits.class)
                        || owner.isAssignableTo(OsTraits.class))
                    && call.getTarget().getRawReturnType().isEquivalentTo(boolean.class)
                    && !allowed.contains(call.getTarget().getName());
              }
            })
        .because("a platform behavior is a seam method, not a trait a shared class branches on");
  }

  /**
   * Toolkit calls whose answer is the platform: the application menu, the tray, the task bar, the
   * system theme setting and the program registry.
   */
  private static final List<String> PLATFORM_QUERIES =
      List.of(
          "org.eclipse.swt.widgets.Display.getSystemMenu",
          "org.eclipse.swt.widgets.Display.getSystemTray",
          "org.eclipse.swt.widgets.Display.getSystemTaskBar",
          "org.eclipse.swt.widgets.Display.isSystemDarkTheme",
          "org.eclipse.swt.program.Program.findProgram");

  /**
   * The classes outside the platform packages that call each boolean or enum value trait, the
   * answers a condition can switch on. Each passes the value on (key names, the default keymap, a
   * file's answer) or computes with it for every value; none chooses platform code with it. A new
   * caller is reviewed and added here; one that would choose platform code calls a seam instead.
   */
  private static final Map<String, List<String>> VALUE_TRAIT_CALLERS =
      Map.of(
          "macKeys",
          List.of(ROOT + ".ui.swt.SwtKeys"),
          "macKeymap",
          List.of(ROOT + ".app.command.Keymap"),
          "takesCtrlShiftZero",
          List.of(ROOT + ".app.command.Keymap"),
          "runsInsteadOfOpening",
          List.of(ROOT + ".app.controller.PathCommands"),
          "keepFileClosedByDefault",
          List.of(ROOT + ".app.config.FollowSettings"),
          "watchServiceByDefault",
          List.of(ROOT + ".core.watch.MonitorSettings"));

  /** Only {@code ui.swt.platform} calls a {@link #PLATFORM_QUERIES} method. */
  @Test
  void platformQueriesOnlyInThePlatformPackage() {
    platformQueriesOnlyInThePlatformPackageRule().check(classes);
  }

  /** A value trait is called only by its {@link #VALUE_TRAIT_CALLERS}. */
  @Test
  void valueTraitsOnlyInReviewedCallers() {
    valueTraitsOnlyInReviewedCallersRule().check(classes);
  }

  @Test
  void auditRulesFailOnTheirFixtures() throws ClassNotFoundException {
    String views = ROOT + ".ui.swt.archfixture.ViewViolations$";
    assertViolations(
        platformQueriesOnlyInThePlatformPackageRule(),
        views + "AsksThePlatform",
        "getSystemMenu",
        "getSystemTray",
        "getSystemTaskBar",
        "isSystemDarkTheme",
        "findProgram");
    assertViolations(
        valueTraitsOnlyInReviewedCallersRule(),
        views + "BranchesOnAValue",
        "macKeys",
        "runsInsteadOfOpening");
  }

  private static ArchRule platformQueriesOnlyInThePlatformPackageRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".ui.swt.platform..")
        .should()
        .callMethodWhere(
            new DescribedPredicate<JavaMethodCall>("call a toolkit platform query") {
              @Override
              public boolean test(JavaMethodCall call) {
                return PLATFORM_QUERIES.contains(
                    call.getTargetOwner().getName() + "." + call.getTarget().getName());
              }
            })
        .because("a platform's answer reaches shared code through a seam method or a value trait");
  }

  private static ArchRule valueTraitsOnlyInReviewedCallersRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackages(ROOT + ".ui.swt.platform..", ROOT + ".core.util.os..")
        .should()
        .callMethodWhere(
            new DescribedPredicate<JavaMethodCall>("call a value trait outside its callers") {
              @Override
              public boolean test(JavaMethodCall call) {
                JavaClass owner = call.getTargetOwner();
                List<String> callers = VALUE_TRAIT_CALLERS.get(call.getTarget().getName());
                if (callers == null
                    || !(owner.isAssignableTo(ROOT + ".ui.swt.platform.PlatformTraits")
                        || owner.isAssignableTo(ROOT + ".core.util.os.OsTraits"))) {
                  return false;
                }
                String origin = call.getOriginOwner().getName();
                int nested = origin.indexOf('$');
                return !callers.contains(nested < 0 ? origin : origin.substring(0, nested));
              }
            })
        .because("a value trait a condition switches on chooses platform code; a seam does that");
  }

  // Log view geometry.

  /** The log view's column geometry and hit testing stay testable with no display. */
  @Test
  void textGridUsesNoSwt() {
    noClasses()
        .that()
        .haveFullyQualifiedName(ROOT + ".ui.swt.TextGrid")
        .or()
        .haveNameMatching(ROOT.replace(".", "\\.") + "\\.ui\\.swt\\.TextGrid\\$.*")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.eclipse.swt..")
        .check(classes);
  }

  @Test
  void layering() {
    noClasses()
        .that()
        .resideInAPackage(CORE)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(APP, UI, LAUNCHER)
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage(APP)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(UI, LAUNCHER)
        .check(classes);
    noClasses()
        .that()
        .resideInAnyPackage(
            ROOT + ".core.io..",
            ROOT + ".core.text..",
            ROOT + ".core.index..",
            ROOT + ".core.search..",
            ROOT + ".core.watch..",
            ROOT + ".core.concurrent..",
            ROOT + ".core.time..",
            ROOT + ".core.rotate..",
            ROOT + ".core.columns..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(ROOT + ".core.model..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".core.columns..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(ROOT + ".core.search..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".app.view..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            ROOT + ".app.controller..",
            ROOT + ".app.config..",
            ROOT + ".app.cli..",
            ROOT + ".app.ipc..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".ui.swt..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            ROOT + ".core.io..", ROOT + ".core.index..", ROOT + ".core.concurrent..")
        .check(classes);
  }

  @Test
  void utilitiesDependOnNoOtherPackage() {
    utilitiesDependOnNoOtherPackageRule().check(classes);
  }

  @Test
  void utilitiesRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        utilitiesDependOnNoOtherPackageRule(),
        ROOT + ".core.util.archfixture.UtilityViolations",
        "core.folder.NamePattern",
        "core.format.JsonStrings");
  }

  /** A class in {@code core.util} depends on no QLogg class outside {@code core.util}. */
  private static ArchRule utilitiesDependOnNoOtherPackageRule() {
    return noClasses()
        .that()
        .resideInAPackage(ROOT + ".core.util..")
        .should()
        .dependOnClassesThat(
            JavaClass.Predicates.resideInAPackage(ROOT + "..")
                .and(JavaClass.Predicates.resideOutsideOfPackage(ROOT + ".core.util..")));
  }

  @Test
  void everyUiTextIsUsed() {
    JavaClass text = classes.get(UiText.class);
    List<String> unused = new ArrayList<String>();
    for (JavaField field : text.getFields()) {
      if (!field.getModifiers().contains(JavaModifier.ENUM)) {
        continue;
      }
      boolean used = false;
      for (JavaAccess<?> access : field.getAccessesToSelf()) {
        if (!access.getOriginOwner().equals(text)) {
          used = true;
        }
      }
      if (!used) {
        unused.add(field.getName());
      }
    }
    assertEquals(List.of(), unused);
  }

  @Test
  void noPackageCycles() {
    slices().matching(ROOT + ".(**)").should().beFreeOfCycles().check(classes);
  }

  /** Only the launcher configures handlers, levels or the {@code LogManager}. */
  @Test
  void julOnlyInLauncher() {
    noClasses()
        .that()
        .resideOutsideOfPackage(LAUNCHER)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("java.util.logging..", "org.slf4j.jul..")
        .check(classes);
  }

  @Test
  void noOutboundNetwork() {
    noOutboundNetworkRule().check(classes);
    noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".app.ipc..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.nio.channels.SocketChannel")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.nio.channels.ServerSocketChannel")
        .check(classes);
  }

  private static ArchRule noOutboundNetworkRule() {
    return noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAPackage("java.net.http..")
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(URLConnection.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(Socket.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ServerSocket.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(DatagramSocket.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(DatagramChannel.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(AsynchronousSocketChannel.class)
        .orShould()
        .callMethodWhere(call(URL.class, "openStream|getContent"))
        .orShould()
        .callMethodWhere(call(InetAddress.class, "getByName|getAllByName"));
  }

  @Test
  void noProcessesDeserializationOrWeakRandom() {
    noProcessesDeserializationOrWeakRandomRule().check(classes);
  }

  private static ArchRule noProcessesDeserializationOrWeakRandomRule() {
    return noClasses()
        .should()
        .dependOnClassesThat()
        .areAssignableTo(ProcessBuilder.class)
        .orShould()
        .callMethodWhere(call(Runtime.class, "exec"))
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ObjectInputStream.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ObjectOutputStream.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(XMLDecoder.class)
        .orShould()
        .dependOnClassesThat(weakRandom());
  }

  @Test
  void onlyMainExits() {
    onlyMainExitsRule().check(classes);
  }

  private static ArchRule onlyMainExitsRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(Main.class.getName())
        .should()
        .callMethod(System.class, "exit", int.class)
        .orShould()
        .callMethodWhere(call(Runtime.class, "exit|halt"));
  }

  @Test
  void noSyncExec() {
    noSyncExecRule().check(classes);
  }

  private static ArchRule noSyncExecRule() {
    return noClasses().should().callMethodWhere(call(Display.class, "syncExec|syncCall"));
  }

  @Test
  void onlyTheDispatcherPostsToTheDisplay() {
    onlyTheDispatcherPostsToTheDisplayRule().check(classes);
  }

  private static ArchRule onlyTheDispatcherPostsToTheDisplayRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ROOT + ".ui.swt.SwtUiDispatcher")
        .should()
        .callMethodWhere(call(Display.class, "asyncExec|timerExec|execute"));
  }

  /** The process, exit, network and Display rules each fail on a fixture call per branch. */
  @Test
  void securityAndDisplayRulesFailOnTheirFixtures() throws ClassNotFoundException {
    String views = ROOT + ".ui.swt.archfixture.ViewViolations$";
    String controllers = ROOT + ".app.controller.archfixture.ControllerViolations$";
    assertViolations(
        noOutboundNetworkRule(),
        controllers + "ReachesNetwork",
        "java.net.ServerSocket",
        "java.net.DatagramSocket",
        "java.nio.channels.DatagramChannel",
        "java.nio.channels.AsynchronousSocketChannel",
        "URL.getContent",
        "InetAddress.getByName",
        "InetAddress.getAllByName");
    assertViolations(
        noProcessesDeserializationOrWeakRandomRule(),
        controllers + "StartsProcesses",
        "Runtime.exec(java.lang.String, [Ljava.lang.String;)",
        "Runtime.exec([Ljava.lang.String;, [Ljava.lang.String;, java.io.File)");
    assertViolations(onlyMainExitsRule(), controllers + "Exits", "Runtime.exit");
    assertViolations(noSyncExecRule(), views + "WaitsOnDisplay", "Display.syncCall");
    assertViolations(
        onlyTheDispatcherPostsToTheDisplayRule(), views + "WaitsOnDisplay", "Display.execute");
  }

  @Test
  void viewsNeverBlock() {
    viewsNeverBlockRule().check(classes);
  }

  private static ArchRule viewsNeverBlockRule() {
    return noClasses()
        .that()
        .resideInAnyPackage(VIEWS)
        .should()
        .callMethodWhere(call(Future.class, "get"))
        .orShould()
        .callMethodWhere(call(Thread.class, "join|sleep"))
        .orShould()
        .callMethodWhere(call(Object.class, "wait"))
        .orShould()
        .callMethodWhere(call(Lock.class, "lock|lockInterruptibly"))
        .orShould()
        .callMethodWhere(call(Condition.class, "await.*"))
        .orShould()
        .callMethodWhere(call(Semaphore.class, "acquire.*"))
        .orShould()
        .callMethodWhere(call(BlockingQueue.class, "put|take"))
        .orShould()
        .callMethodWhere(call(CountDownLatch.class, "await"))
        .orShould()
        .callMethodWhere(call(OperationHandle.class, "await"))
        .orShould()
        .callMethodWhere(call(Charset.class, "availableCharsets"))
        .orShould()
        .callMethodWhere(call(Charsets.class, "available"))
        .orShould()
        .dependOnClassesThat()
        .resideInAPackage("java.nio.channels..")
        .because("the UI thread never waits");
  }

  @Test
  void controllersNeverWait() {
    noClasses()
        .that()
        .resideInAnyPackage(ROOT + ".app.controller..", ROOT + ".app.command..")
        .should()
        .callMethodWhere(call(Future.class, "get"))
        .orShould()
        .callMethodWhere(call(Thread.class, "join|sleep"))
        .orShould()
        .callMethodWhere(call(OperationHandle.class, "await"))
        .orShould()
        .callMethodWhere(call(CountDownLatch.class, "await"))
        .check(classes);
  }

  @Test
  void onlyWorkersCreatesThreads() {
    onlyWorkersCreatesThreadsRule().check(classes);
  }

  private static ArchRule onlyWorkersCreatesThreadsRule() {
    return noClasses()
        .that()
        .doNotBelongToAnyOf(Workers.class)
        .should()
        .callConstructorWhere(newInstanceOf(Thread.class))
        .orShould()
        .callMethodWhere(call(Thread.class, "ofVirtual|ofPlatform|startVirtualThread"))
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(Executors.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ThreadPoolExecutor.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ScheduledThreadPoolExecutor.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ForkJoinPool.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(Timer.class)
        // The common ForkJoinPool runs these without a reference to ForkJoinPool. Checkstyle
        // rejects a parallelStream call in source.
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(ForkJoinTask.class)
        .orShould()
        .callMethodWhere(call(java.util.concurrent.CompletableFuture.class, ".*Async"))
        .orShould()
        .callMethodWhere(call(java.util.stream.BaseStream.class, "parallel"))
        .orShould()
        .callMethodWhere(call(Arrays.class, "parallel.*"))
        .orShould()
        .beAssignableTo(ThreadFactory.class)
        .orShould()
        .beAssignableTo(AbstractExecutorService.class);
  }

  // Boundary rules for views and controllers; each is shown to fail on a fixture below.

  @Test
  void viewsUseSearchAndWatchEnumsOnly() {
    viewsUseSearchAndWatchEnumsOnlyRule().check(classes);
  }

  private static ArchRule viewsUseSearchAndWatchEnumsOnlyRule() {
    return noClasses()
        .that()
        .resideInAPackage(ROOT + ".ui.swt..")
        .should()
        .dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(ROOT + ".core.search..", ROOT + ".core.watch..")
                .and(DescribedPredicate.not(enums())));
  }

  @Test
  void viewsDoNotReachTheDocumentModel() {
    viewsDoNotReachTheDocumentModelRule().check(classes);
  }

  private static ArchRule viewsDoNotReachTheDocumentModelRule() {
    return noClasses()
        .that()
        .resideInAnyPackage(VIEWS)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(LogDocument.class, MutableLineSet.class, LineReader.class, FoldMap.class);
  }

  @Test
  void viewsDoNotMoveTheViewport() {
    viewsDoNotMoveTheViewportRule().check(classes);
  }

  private static ArchRule viewsDoNotMoveTheViewportRule() {
    return noClasses()
        .that()
        .resideInAnyPackage(VIEWS)
        .should()
        .callMethodWhere(
            call(Viewport.class, "set.*|resize|scrollToLine|scrollBy|scrollColumnsBy|ensure.*"))
        .because("the controller owns the viewport");
  }

  @Test
  void controllersReturnNoMutableSets() {
    controllersReturnNoMutableSetsRule().check(classes);
  }

  private static ArchRule controllersReturnNoMutableSetsRule() {
    return noMethods()
        .that()
        .arePublic()
        .and()
        .areDeclaredInClassesThat()
        .resideInAPackage(ROOT + ".app.controller..")
        .should()
        .haveRawReturnType(MutableLineSet.class);
  }

  @Test
  void controllersReadNoSystemProperties() {
    controllersReadNoSystemPropertiesRule().check(classes);
  }

  private static ArchRule controllersReadNoSystemPropertiesRule() {
    return noClasses()
        .that()
        .resideInAPackage(ROOT + ".app.controller..")
        .should()
        .callMethodWhere(call(System.class, "getProperty|getProperties|getenv"))
        .because("the platform is passed in, so tests can choose it");
  }

  // Copy and save export.
  @Test
  void textExporterReachesNoView() {
    noClasses()
        .that()
        .haveFullyQualifiedName(ROOT + ".app.controller.TextExporter")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(ROOT + ".app.view..")
        .because("the exporter runs on a worker; its caller posts the result to the UI thread")
        .check(classes);
  }

  // Highlight rule composition and validation.

  @Test
  void viewsDoNotComposeOrCompileHighlightRules() {
    viewsDoNotComposeOrCompileHighlightRulesRule().check(classes);
  }

  private static ArchRule viewsDoNotComposeOrCompileHighlightRulesRule() {
    return noClasses()
        .that()
        .resideInAnyPackage(VIEWS)
        .should()
        .callMethodWhere(call(Highlighter.class, "of|compile"))
        .because(
            "HighlightComposer and HighlightSetsEditor in qlogg-app compose and validate rules");
  }

  @Test
  void highlightRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        viewsDoNotComposeOrCompileHighlightRulesRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$CompilesHighlightRule",
        "Highlighter.compile",
        "Highlighter.of");
  }

  // Menu command state.

  @Test
  void viewsTakeCheckStateFromCommandStates() {
    viewsTakeCheckStateFromCommandStatesRule().check(classes);
  }

  @Test
  void checkStateRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        viewsTakeCheckStateFromCommandStatesRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$ReadsCheckState",
        "ThemeController.mode",
        "isFavorite",
        "!palette");
  }

  /** The getters only a check item's state reads; {@code CommandStates} maps them. */
  private static ArchRule viewsTakeCheckStateFromCommandStatesRule() {
    return noClasses()
        .that()
        .resideInAPackage(ROOT + ".ui.swt..")
        .should()
        .callMethodWhere(
            call(
                io.github.ajdailey.qlogg.app.controller.ApplicationController.class,
                "isFavorite|debugLogFile|runSearchOnAdd"))
        .orShould()
        .callMethodWhere(call(io.github.ajdailey.qlogg.app.theme.ThemeController.class, "mode"))
        .because("CommandStates computes menu state");
  }

  @Test
  void rulesFailOnTheirFixtures() throws ClassNotFoundException {
    String views = ROOT + ".ui.swt.archfixture.ViewViolations$";
    String controllers = ROOT + ".app.controller.archfixture.ControllerViolations$";
    assertViolations(
        viewsUseSearchAndWatchEnumsOnlyRule(),
        views + "UsesWatchRecord",
        "MonitorSettings",
        "!MatchMode");
    assertViolations(
        viewsDoNotReachTheDocumentModelRule(), views + "ReachesDocument", "LogDocument");
    assertViolations(
        viewsDoNotMoveTheViewportRule(), views + "MovesViewport", "scrollBy", "!topLine");
    assertViolations(
        viewsNeverBlockRule(),
        views + "Blocks",
        "Thread.sleep",
        "Object.wait",
        "Lock.lock()",
        "lockInterruptibly",
        "Condition.await",
        "Semaphore.acquire",
        "BlockingQueue.put",
        "BlockingQueue.take",
        "CountDownLatch.await",
        "OperationHandle.await",
        "Charset.availableCharsets");
    assertViolations(
        controllersReturnNoMutableSetsRule(),
        controllers + "ReturnsMutableSet",
        "mutable()",
        "!lines()");
    assertViolations(
        controllersReadNoSystemPropertiesRule(), controllers + "ReadsPlatform", "getProperty");
    // Always on Top.
    assertViolations(
        reflectionOnlyInExceptionClassesRule(),
        views + "ReflectsIntoSwt",
        "Class.forName",
        "Field.setAccessible");
    assertViolations(
        onlyWorkersCreatesThreadsRule(), controllers + "StartsTimer", "java.util.Timer");
    assertViolations(
        onlyWorkersCreatesThreadsRule(),
        controllers + "MakesThreads",
        "java.util.concurrent.ThreadFactory");
    assertViolations(
        onlyWorkersCreatesThreadsRule(),
        controllers + "RunsTasks",
        "java.util.concurrent.AbstractExecutorService");
    assertViolations(
        onlyWorkersCreatesThreadsRule(),
        controllers + "UsesTheCommonPool",
        "java.util.concurrent.RecursiveAction",
        "CompletableFuture.runAsync",
        "CompletableFuture.supplyAsync",
        "CompletableFuture.thenApplyAsync",
        "IntStream.parallel",
        "Arrays.parallelSort");
  }

  // Document opening.

  @Test
  void onlyTheOpenerOpensDocuments() {
    onlyTheOpenerOpensDocumentsRule().check(classes);
  }

  @Test
  void openerRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        onlyTheOpenerOpensDocumentsRule(),
        ROOT + ".app.controller.archfixture.ControllerViolations$OpensDocuments",
        "LogDocument.open",
        "LogDocument.ofText");
  }

  private static ArchRule onlyTheOpenerOpensDocumentsRule() {
    return noClasses()
        .that()
        .resideInAPackage(APP)
        .and()
        .haveNameNotMatching(
            ROOT.replace(".", "\\.") + "\\.app\\.controller\\.DocumentOpener(\\$.*)?")
        .should()
        .callMethodWhere(call(LogDocument.class, "open|ofText"))
        .because("DocumentOpener closes a document whose open ends after its window closed");
  }

  // Blocking calls in controllers.

  /** Owners and method names of the blocking calls a controller makes only on a worker. */
  private static final List<Blocking> BLOCKING =
      List.of(
          new Blocking(LogDocument.class, "open|ofText|close|closeAll"),
          new Blocking(LineReader.class, "read|readRows|decodedLine|appendText|appendColumns"),
          new Blocking(ByteSource.class, "read|size"),
          new Blocking(LineFinder.class, "find"),
          new Blocking(RangeFinder.class, "around"),
          new Blocking(FoldClassifier.class, "entries"),
          new Blocking(TimeReader.class, "next|at"),
          new Blocking(TimeSearch.class, "locate|locateLinear"),
          new Blocking(TimeSamples.class, "sample"),
          new Blocking(Files.class, ".*"),
          new Blocking(FileChannel.class, ".*"),
          new Blocking(Path.class, "toRealPath"),
          new Blocking(Charset.class, "availableCharsets"),
          new Blocking(Charsets.class, "available"),
          new Blocking(OsTraits.class, "runsInsteadOfOpening"),
          new Blocking(AtomicFiles.class, ".*"),
          new Blocking(SafePaths.class, "logPath|logFile|settingsDirectory"),
          new Blocking(ConfigStore.class, "load|save|write"),
          new Blocking(SessionStore.class, "load|loadWindows|save|saveWindows|writeWindows"),
          new Blocking(PositionStore.class, "load|save"),
          new Blocking(FilterFile.class, "read|write"),
          new Blocking(HighlightFile.class, "read|write"),
          new Blocking(UserGuide.class, "writeCopy"),
          new Blocking(Workers.class, "close"));

  private record Blocking(Class<?> owner, String names) {}

  @Test
  void controllersBlockOnlyOnWorkers() {
    controllersBlockOnlyOnWorkersRule().check(classes);
  }

  @Test
  void workerMethodsRunOnWorkers() {
    workerMethodsRunOnWorkersRule().check(classes);
  }

  @Test
  void workerThreadRulesFailOnTheirFixture() throws ClassNotFoundException {
    String fixture = ROOT + ".app.controller.archfixture.ControllerViolations$BlocksOnUiThread";
    assertViolations(
        controllersBlockOnlyOnWorkersRule(),
        fixture,
        "Files.exists",
        "LogDocument.close",
        "OsTraits.runsInsteadOfOpening",
        "Files.isSymbolicLink",
        "Files.isReadable",
        "!Files.size",
        "!Files.isDirectory");
    assertViolations(
        workerMethodsRunOnWorkersRule(), fixture, "uiCallsWorker", "!workerCallsWorker");
  }

  private static ArchRule controllersBlockOnlyOnWorkersRule() {
    return classes()
        .that()
        .resideInAPackage(ROOT + ".app.controller..")
        .should(
            new ArchCondition<JavaClass>(
                "make blocking calls only from @WorkerThread methods or tasks submitted to"
                    + " Workers") {
              @Override
              public void check(JavaClass type, ConditionEvents events) {
                for (JavaCall<?> call : type.getCodeUnitCallsFromSelf()) {
                  if (blocking(call) && !onWorker(call.getOrigin())) {
                    events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                  }
                }
              }
            })
        .because("the UI thread never waits");
  }

  /** Annotating a UI-thread method does not pass the blocking rule: its caller fails this one. */
  private static ArchRule workerMethodsRunOnWorkersRule() {
    return methods()
        .that()
        .areAnnotatedWith(WorkerThread.class)
        .should(
            new ArchCondition<JavaMethod>(
                "be called only from @WorkerThread methods or tasks submitted to Workers") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                for (JavaCall<?> call : method.getCallsOfSelf()) {
                  if (!onWorker(call.getOrigin())) {
                    events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                  }
                }
              }
            });
  }

  private static boolean blocking(JavaCall<?> call) {
    for (Blocking entry : BLOCKING) {
      if (call.getTargetOwner().isAssignableTo(entry.owner())
          && call.getTarget().getName().matches(entry.names())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reports whether code runs on a worker: a method annotated {@code @WorkerThread}, or {@code run}
   * of an anonymous {@code Runnable} whose creating method submits it to {@code Workers}, directly
   * or through a method of its own class that takes a {@code Runnable} and submits it. The creating
   * method must create no other anonymous {@code Runnable} and take no method reference, since
   * bytecode does not show which task it submits.
   */
  private static boolean onWorker(JavaCodeUnit origin) {
    if (origin.isAnnotatedWith(WorkerThread.class)) {
      return true;
    }
    JavaClass task = origin.getOwner();
    if (!origin.getName().equals("run") || !anonymousRunnable(task)) {
      return false;
    }
    Optional<JavaCodeUnit> creator = task.getEnclosingCodeUnit();
    if (creator.isEmpty() || !submits(creator.get(), true)) {
      return false;
    }
    int created = creator.get().getMethodReferencesFromSelf().size();
    for (JavaCall<?> call : creator.get().getCallsFromSelf()) {
      if (call.getTarget().getName().equals("<init>") && anonymousRunnable(call.getTargetOwner())) {
        created++;
      }
    }
    return created == 1;
  }

  /** Reports whether a method submits a task to {@code Workers}, directly or through a helper. */
  private static boolean submits(JavaCodeUnit code, boolean viaHelper) {
    for (JavaCall<?> call : code.getCallsFromSelf()) {
      if (call.getTargetOwner().isEquivalentTo(Workers.class)
          && call.getTarget().getName().matches("tryStart|start|executeCpu")) {
        return true;
      }
      if (viaHelper && call.getTargetOwner().equals(code.getOwner())) {
        Optional<? extends JavaCodeUnit> helper = call.getTarget().resolveMember();
        if (helper.isPresent() && takesRunnable(helper.get()) && submits(helper.get(), false)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean takesRunnable(JavaCodeUnit code) {
    for (JavaClass type : code.getRawParameterTypes()) {
      if (type.isEquivalentTo(Runnable.class)) {
        return true;
      }
    }
    return false;
  }

  private static boolean anonymousRunnable(JavaClass type) {
    return type.isAnonymousClass() && type.isAssignableTo(Runnable.class);
  }

  /**
   * Checks a rule against one fixture class and the classes nested in it: every fragment appears in
   * the failure report, and no fragment prefixed {@code !} does.
   */
  private static void assertViolations(ArchRule rule, String fixture, String... fragments)
      throws ClassNotFoundException {
    Class<?> type = Class.forName(fixture);
    final String file = "/" + type.getName().substring(type.getPackageName().length() + 1);
    JavaClasses imported =
        new ClassFileImporter()
            .withImportOption(
                new ImportOption() {
                  @Override
                  public boolean includes(Location location) {
                    return location.contains(file + ".class") || location.contains(file + "$");
                  }
                })
            .importPackages(type.getPackageName());
    EvaluationResult result = rule.evaluate(imported);
    String report = String.join("\n", result.getFailureReport().getDetails());
    assertTrue(result.hasViolation(), fixture);
    for (String fragment : fragments) {
      if (fragment.startsWith("!")) {
        assertFalse(report.contains(fragment.substring(1)), fixture + ": " + report);
      } else {
        assertTrue(report.contains(fragment), fixture + " lacks " + fragment + ": " + report);
      }
    }
  }

  /** Matches {@code java.util.Random} and its subtypes except {@code SecureRandom}. */
  private static DescribedPredicate<JavaClass> weakRandom() {
    return new DescribedPredicate<JavaClass>("assignable to Random but not SecureRandom") {
      @Override
      public boolean test(JavaClass type) {
        return type.isAssignableTo(Random.class) && !type.isAssignableTo(SecureRandom.class);
      }
    };
  }

  private static DescribedPredicate<JavaClass> enums() {
    return new DescribedPredicate<JavaClass>("enums") {
      @Override
      public boolean test(JavaClass type) {
        return type.isEnum();
      }
    };
  }

  /** Matches a call to a method of {@code owner} or a subtype whose name matches the pattern. */
  private static DescribedPredicate<JavaMethodCall> call(
      final Class<?> owner, final String namePattern) {
    return new DescribedPredicate<JavaMethodCall>(
        "call " + owner.getSimpleName() + "." + namePattern) {
      @Override
      public boolean test(JavaMethodCall call) {
        return call.getTarget().getName().matches(namePattern)
            && call.getTarget().getOwner().isAssignableTo(owner);
      }
    };
  }

  /** Matches a constructor call creating {@code type} or a subtype. */
  private static DescribedPredicate<JavaConstructorCall> newInstanceOf(final Class<?> type) {
    return new DescribedPredicate<JavaConstructorCall>("new " + type.getSimpleName()) {
      @Override
      public boolean test(JavaConstructorCall call) {
        return call.getTarget().getOwner().isAssignableTo(type);
      }
    };
  }

  // Window controller hosts.

  /** A helper of {@code ApplicationController} reaches its window through its host interface. */
  @Test
  void helpersDoNotReachTheWindowSet() {
    helpersDoNotReachTheWindowSetRule().check(classes);
  }

  @Test
  void windowSetRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        helpersDoNotReachTheWindowSetRule(),
        ROOT + ".app.controller.archfixture.ControllerViolations$ReachesTheWindowSet",
        "ApplicationController.windowSet()");
  }

  private static ArchRule helpersDoNotReachTheWindowSetRule() {
    return noClasses()
        .that()
        .resideInAPackage(ROOT + ".app.controller..")
        .and()
        .haveNameNotMatching(
            ROOT.replace(".", "\\.")
                + "\\.app\\.controller\\.(WindowSet|ApplicationController)(\\$.*)?")
        .should()
        .callMethodWhere(call(ApplicationController.class, "windowSet"))
        .because("each helper takes a narrow host, as DocumentController takes DocumentHost");
  }

  // Document services.

  /** Main code builds {@code DocumentServices} with the held lock or copies existing services. */
  @Test
  void mainCodeCarriesTheHeldLock() {
    mainCodeCarriesTheHeldLockRule().check(classes);
  }

  @Test
  void heldLockRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        mainCodeCarriesTheHeldLockRule(),
        ROOT + ".app.controller.archfixture.ControllerViolations$DropsTheHeldLock",
        "DocumentServices.<init>");
  }

  private static ArchRule mainCodeCarriesTheHeldLockRule() {
    return noClasses()
        .should()
        .callConstructor(
            DocumentServices.class,
            Workers.class,
            Indexer.class,
            SearchEngine.class,
            BlockCache.class,
            MemoryBudget.class,
            Charset.class)
        .because("the constructor without the held lock is for tests and benchmarks");
  }

  // Operating system checks.

  /**
   * {@code os.name} is read only in {@code core.util.os}, whose {@code OsTraits} answers for it.
   */
  @Test
  void osNameOnlyInTheOsSeam() {
    osNameOnlyInTheOsSeamRule().check(classes);
  }

  /** Controllers take the system, or values from it, from their caller. */
  @Test
  void controllersDoNotAskTheOs() {
    controllersDoNotAskTheOsRule().check(classes);
  }

  @Test
  void osRulesFailOnTheirFixtures() throws ClassNotFoundException {
    String controllers = ROOT + ".app.controller.archfixture.ControllerViolations$";
    assertViolations(
        osNameOnlyInTheOsSeamRule(), controllers + "ReadsPlatform", "ReadsPlatform holds");
    assertViolations(
        controllersDoNotAskTheOsRule(), controllers + "AsksTheOs", "OsTraits.current()");
  }

  private static ArchRule osNameOnlyInTheOsSeamRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".core.util.os..")
        .should(holdTheString("os.name"));
  }

  private static ArchRule controllersDoNotAskTheOsRule() {
    return noClasses()
        .that()
        .resideInAPackage(ROOT + ".app.controller..")
        .should()
        .callMethod(OsTraits.class, "current");
  }

  // Collection declarations.

  /** Only {@code Charsets} looks charsets up, so every name resolves windows-1252 the same way. */
  @Test
  void charsetLookupsOnlyInCharsets() {
    charsetLookupsOnlyInCharsetsRule().check(classes);
  }

  @Test
  void charsetRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        charsetLookupsOnlyInCharsetsRule(),
        ROOT + ".core.archfixture.CoreViolations$LooksUpCharsets",
        "Charset.forName",
        "Charset.availableCharsets");
  }

  private static ArchRule charsetLookupsOnlyInCharsetsRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ROOT + ".core.text.Charsets")
        .should()
        .callMethodWhere(call(Charset.class, "forName|availableCharsets"))
        .because("Charsets resolves windows-1252 and its aliases to Windows1252");
  }

  /**
   * Field, parameter and return types, generic arguments included, name a collection interface
   * ({@code List}, {@code Map}, {@code Deque}), never a class that implements {@code Collection} or
   * {@code Map}; the class appears only after {@code new}. An exception names the class and the
   * class-only member it calls. Locals are a review item.
   *
   * <p>Exception: {@code Properties}, for {@code getProperty}, {@code load} and {@code store}.
   */
  @Test
  void collectionsDeclaredByInterface() {
    collectionsDeclaredByInterfaceRule().check(classes);
  }

  @Test
  void collectionRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        collectionsDeclaredByInterfaceRule(),
        ROOT + ".core.archfixture.CoreViolations$DeclaresClasses",
        "items> declares java.util.ArrayList",
        "index(java.util.Map)> declares java.util.TreeSet",
        "pending()> declares java.util.ArrayDeque",
        "!names>");
  }

  private static ArchRule collectionsDeclaredByInterfaceRule() {
    return classes()
        .should(
            new ArchCondition<JavaClass>("declare collection interfaces, not classes") {
              @Override
              public void check(JavaClass type, ConditionEvents events) {
                for (JavaField field : type.getFields()) {
                  reportCollectionClasses(field, field.getType(), events);
                }
                for (JavaCodeUnit unit : type.getCodeUnits()) {
                  reportCollectionClasses(unit, unit.getReturnType(), events);
                  for (JavaType parameter : unit.getParameterTypes()) {
                    reportCollectionClasses(unit, parameter, events);
                  }
                }
              }
            });
  }

  private static void reportCollectionClasses(
      JavaMember member, JavaType declared, ConditionEvents events) {
    for (JavaClass raw : declared.getAllInvolvedRawTypes()) {
      if (!raw.isInterface()
          && !raw.isEquivalentTo(Properties.class)
          && (raw.isAssignableTo(Collection.class) || raw.isAssignableTo(Map.class))) {
        events.add(
            SimpleConditionEvent.violated(
                member, "<" + member.getFullName() + "> declares " + raw.getName()));
      }
    }
  }

  /**
   * Matches a class whose constant pool holds the text as a {@code CONSTANT_Utf8} entry: a string
   * literal, or a constant inlined from another class.
   */
  private static ArchCondition<JavaClass> holdTheString(final String text) {
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    final byte[] entry = new byte[utf8.length + 3];
    entry[0] = 1;
    entry[1] = (byte) (utf8.length >> 8);
    entry[2] = (byte) utf8.length;
    System.arraycopy(utf8, 0, entry, 3, utf8.length);
    return new ArchCondition<JavaClass>("hold the string \"" + text + "\"") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        byte[] bytes = classFile(type);
        boolean holds = false;
        for (int i = 0; !holds && i + entry.length <= bytes.length; i++) {
          holds = Arrays.equals(bytes, i, i + entry.length, entry, 0, entry.length);
        }
        events.add(
            new SimpleConditionEvent(
                type,
                holds,
                type.getName() + (holds ? " holds" : " does not hold") + " \"" + text + "\""));
      }
    };
  }

  /**
   * Matches a class whose constant pool holds a {@code CONSTANT_Utf8} entry starting with the
   * prefix.
   */
  private static ArchCondition<JavaClass> holdAStringStarting(final String prefix) {
    final byte[] utf8 = prefix.getBytes(StandardCharsets.UTF_8);
    return new ArchCondition<JavaClass>("hold a string starting \"" + prefix + "\"") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        byte[] bytes = classFile(type);
        boolean holds = false;
        for (int i = 3; !holds && i + utf8.length <= bytes.length; i++) {
          holds =
              bytes[i - 3] == 1
                  && (((bytes[i - 2] & 0xff) << 8) | (bytes[i - 1] & 0xff)) >= utf8.length
                  && Arrays.equals(bytes, i, i + utf8.length, utf8, 0, utf8.length);
        }
        events.add(
            new SimpleConditionEvent(
                type,
                holds,
                type.getName()
                    + (holds ? " holds" : " does not hold")
                    + " a string starting \""
                    + prefix
                    + "\""));
      }
    };
  }

  private static byte[] classFile(JavaClass type) {
    String file = type.getName().replace('.', '/') + ".class";
    try (InputStream in = ArchitectureTest.class.getClassLoader().getResourceAsStream(file)) {
      if (in == null) {
        throw new AssertionError("No class file " + file);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // Dialog colors.

  /**
   * A code unit that creates a {@code Shell} with an owning shell also calls {@code
   * DialogColors.apply}, as {@code Dialogs.shell} does, so a dialog built another way still takes
   * the dark dialog colors on win32. The main window is out of scope: it is created with a {@code
   * Display}. No owned shell stays native.
   */
  @Test
  void ownedShellsTakeDialogColors() {
    ownedShellsTakeDialogColorsRule().check(classes);
  }

  @Test
  void dialogColorsRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        ownedShellsTakeDialogColorsRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$OwnsUncoloredShell",
        "OwnsUncoloredShell.open");
  }

  private static ArchRule ownedShellsTakeDialogColorsRule() {
    final String shell = "org.eclipse.swt.widgets.Shell";
    final String colors = ROOT + ".ui.swt.platform.DialogColors";
    return codeUnits()
        .should(
            new ArchCondition<JavaCodeUnit>(
                "call DialogColors.apply when creating an owned Shell") {
              @Override
              public void check(JavaCodeUnit unit, ConditionEvents events) {
                boolean owned = false;
                for (JavaConstructorCall call : unit.getConstructorCallsFromSelf()) {
                  if (call.getTargetOwner().isAssignableTo(shell)) {
                    for (JavaClass parameter : call.getTarget().getRawParameterTypes()) {
                      owned |= parameter.isAssignableTo(shell);
                    }
                  }
                }
                if (!owned) {
                  return;
                }
                for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                  if (call.getTargetOwner().isAssignableTo(colors)
                      && call.getName().equals("apply")) {
                    return;
                  }
                }
                events.add(
                    SimpleConditionEvent.violated(
                        unit, unit.getFullName() + " creates an owned Shell without DialogColors"));
              }
            })
        .because("win32 draws a dialog body light under a dark start unless it is colored");
  }

  // Icons.

  /**
   * Item and button icons come from {@code ToolIcons}, which picks the ink for the background they
   * show on. The tab dots ({@code TabStrip} and {@code TabList}) and {@code NativeCellImages},
   * which sets the ToolIcons images a table column takes on a table item, are the exemptions.
   */
  @Test
  void iconsOnlyThroughToolIcons() {
    iconsOnlyThroughToolIconsRule().check(classes);
  }

  @Test
  void iconRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        iconsOnlyThroughToolIconsRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$SetsIcon",
        "ToolItem.setImage",
        "Button.setImage");
  }

  private static ArchRule iconsOnlyThroughToolIconsRule() {
    final List<String> exempt =
        List.of(
            ROOT + ".ui.swt.ToolIcons",
            ROOT + ".ui.swt.TabStrip",
            ROOT + ".ui.swt.TabList",
            ROOT + ".ui.swt.platform.NativeCellImages");
    return noClasses()
        .that(
            new DescribedPredicate<JavaClass>("are not ToolIcons or an exemption") {
              @Override
              public boolean test(JavaClass type) {
                for (String name : exempt) {
                  if (type.getName().equals(name) || type.getName().startsWith(name + "$")) {
                    return false;
                  }
                }
                return true;
              }
            })
        .should()
        .callMethodWhere(call(Item.class, "setImage"))
        .orShould()
        .callMethodWhere(call(Button.class, "setImage"))
        .because("ToolIcons inks an icon for the background it shows on");
  }

  /**
   * A code unit that creates an owned shell calls {@code Dialogs.inheritIcons}, so every dialog and
   * tool window carries the main window's icons. The main window creates the one top-level shell
   * and sets the images.
   */
  @Test
  void ownedShellsInheritIcons() {
    ownedShellsInheritIconsRule().check(classes);
  }

  @Test
  void inheritIconsRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        ownedShellsInheritIconsRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$OwnsUncoloredShell",
        "new Shell without Dialogs.inheritIcons");
  }

  private static ArchRule ownedShellsInheritIconsRule() {
    return classes()
        .should(
            new ArchCondition<JavaClass>("call Dialogs.inheritIcons where they create a Shell") {
              @Override
              public void check(JavaClass type, ConditionEvents events) {
                for (JavaCodeUnit unit : type.getCodeUnits()) {
                  for (JavaConstructorCall call : unit.getConstructorCallsFromSelf()) {
                    if (call.getTargetOwner().isAssignableTo(Shell.class)
                        && !topLevelInMainWindow(call)
                        && !callsInheritIcons(unit)) {
                      events.add(
                          SimpleConditionEvent.violated(
                              call,
                              "new Shell without Dialogs.inheritIcons in "
                                  + call.getDescription()));
                    }
                  }
                }
              }
            });
  }

  private static boolean topLevelInMainWindow(JavaConstructorCall call) {
    return call.getOriginOwner().getName().equals(ROOT + ".ui.swt.MainWindow")
        && call.getTarget().getRawParameterTypes().get(0).isAssignableTo(Display.class);
  }

  private static boolean callsInheritIcons(JavaCodeUnit unit) {
    for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
      if (call.getTarget().getName().equals("inheritIcons")
          && call.getTargetOwner().getName().equals(ROOT + ".ui.swt.Dialogs")) {
        return true;
      }
    }
    return false;
  }

  // Dialog tabs.

  /**
   * Only {@code ui.swt.platform} creates a native {@code TabFolder}; a dialog takes its folder from
   * {@code PlatformTraits.dialogTabs}, which gives a dark-start win32 dialog a dark folder.
   */
  @Test
  void tabFoldersOnlyThroughDialogTabs() {
    tabFoldersOnlyThroughDialogTabsRule().check(classes);
  }

  @Test
  void dialogTabsRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        tabFoldersOnlyThroughDialogTabsRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$CreatesTabFolder",
        "CreatesTabFolder.open");
  }

  private static ArchRule tabFoldersOnlyThroughDialogTabsRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".ui.swt.platform..")
        .should()
        .callConstructorWhere(newInstanceOf(org.eclipse.swt.widgets.TabFolder.class))
        .because("a native TabFolder keeps a light tab row under a dark win32 start");
  }

  @Test
  void gridLinesOnlyThroughDialogColors() {
    gridLinesOnlyThroughDialogColorsRule().check(classes);
  }

  @Test
  void gridLinesRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        gridLinesOnlyThroughDialogColorsRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$ShowsGridLines",
        "ShowsGridLines.open");
  }

  private static ArchRule gridLinesOnlyThroughDialogColorsRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".ui.swt.platform..")
        .should()
        .callMethodWhere(call(org.eclipse.swt.widgets.Table.class, "setLinesVisible"))
        .because("win32 draws native grid lines light under the dark theme; use Dialogs.gridLines");
  }

  /** The class that holds SWT's win32 header divider color key, a Public SWT API exception. */
  private static final String HEADER_LINE_KEY_CLASS = ROOT + ".ui.swt.platform.Win32HeaderLines";

  @Test
  void headerLineKeyOnlyInWin32HeaderLines() {
    headerLineKeyOnlyInWin32HeaderLinesRule().check(classes);
  }

  @Test
  void headerLineKeyRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        headerLineKeyOnlyInWin32HeaderLinesRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$SetsHeaderLineColor",
        "SetsHeaderLineColor holds a string starting \"org.eclipse.swt.internal.win32.Table\"");
  }

  private static ArchRule headerLineKeyOnlyInWin32HeaderLinesRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(HEADER_LINE_KEY_CLASS)
        .should(holdAStringStarting("org.eclipse.swt.internal.win32.Table"))
        .because(
            "the header divider color key names an SWT internal; only Win32HeaderLines sets it");
  }

  @Test
  void nativeChoosersOnlyThroughNativeDialogs() {
    nativeChoosersOnlyThroughNativeDialogsRule().check(classes);
  }

  @Test
  void nativeChooserRuleFailsOnItsFixture() throws ClassNotFoundException {
    assertViolations(
        nativeChoosersOnlyThroughNativeDialogsRule(),
        ROOT + ".ui.swt.archfixture.ViewViolations$OpensFileDialog",
        "OpensFileDialog.choose");
  }

  private static ArchRule nativeChoosersOnlyThroughNativeDialogsRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ROOT + ".ui.swt.NativeDialogs")
        .should()
        .callMethodWhere(call(org.eclipse.swt.widgets.Dialog.class, "open"))
        .because("a window rebuild waits only for a chooser NativeDialogs counts");
  }
}
