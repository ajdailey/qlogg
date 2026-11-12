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
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
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
import io.github.ajdailey.qlogg.app.controller.WorkerThread;
import io.github.ajdailey.qlogg.app.highlight.HighlightFile;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.search.FilterFile;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.io.AtomicFiles;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.model.FoldClassifier;
import io.github.ajdailey.qlogg.core.model.FoldMap;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.LineFinder;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.Viewport;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.RangeFinder;
import io.github.ajdailey.qlogg.core.time.TimeReader;
import io.github.ajdailey.qlogg.core.time.TimeSamples;
import io.github.ajdailey.qlogg.core.time.TimeSearch;
import java.beans.XMLDecoder;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.AccessibleObject;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Timer;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
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

  // Always on Top.

  /** The classes that reach SWT internals by reflection, which no import shows. */
  private static final String[] SWT_REFLECTION = {
    ROOT + ".ui.swt.Win32Stacking", ROOT + ".ui.swt.CocoaStacking", ROOT + ".ui.swt.GtkX11Stacking"
  };

  /** Only the Always on Top platform classes load classes by name or open members. */
  @Test
  void reflectionOnlyInStackingClasses() {
    reflectionOnlyInStackingClassesRule().check(classes);
  }

  private static ArchRule reflectionOnlyInStackingClassesRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(SWT_REFLECTION[0])
        .and()
        .doNotHaveFullyQualifiedName(SWT_REFLECTION[1])
        .and()
        .doNotHaveFullyQualifiedName(SWT_REFLECTION[2])
        .should()
        .callMethodWhere(
            call(Class.class, "forName|getDeclaredField|getDeclaredMethod|getField|getMethod"))
        .orShould()
        .callMethodWhere(call(AccessibleObject.class, "setAccessible|trySetAccessible"))
        .because("SWT internals differ per platform; only these classes reach them");
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
            ROOT + ".core.time..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(ROOT + ".core.model..")
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
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".core.util..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            ROOT + ".core.concurrent..",
            ROOT + ".core.io..",
            ROOT + ".core.text..",
            ROOT + ".core.index..",
            ROOT + ".core.search..",
            ROOT + ".core.watch..",
            ROOT + ".core.model..",
            ROOT + ".core.time..",
            APP,
            UI,
            LAUNCHER)
        .check(classes);
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
    noClasses()
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
        .callMethod(URL.class, "openStream")
        .check(classes);
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

  @Test
  void noProcessesDeserializationOrWeakRandom() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .areAssignableTo(ProcessBuilder.class)
        .orShould()
        .callMethod(Runtime.class, "exec", String.class)
        .orShould()
        .callMethod(Runtime.class, "exec", String[].class)
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
        .dependOnClassesThat(weakRandom())
        .check(classes);
  }

  @Test
  void onlyMainExits() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(Main.class.getName())
        .should()
        .callMethod(System.class, "exit", int.class)
        .orShould()
        .callMethod(Runtime.class, "halt", int.class)
        .check(classes);
  }

  @Test
  void noSyncExec() {
    noClasses()
        .should()
        .callMethod("org.eclipse.swt.widgets.Display", "syncExec", Runnable.class.getName())
        .check(classes);
  }

  @Test
  void onlyTheDispatcherPostsToTheDisplay() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ROOT + ".ui.swt.SwtUiDispatcher")
        .should()
        .callMethod("org.eclipse.swt.widgets.Display", "asyncExec", Runnable.class.getName())
        .orShould()
        .callMethod("org.eclipse.swt.widgets.Display", "timerExec", "int", Runnable.class.getName())
        .check(classes);
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
        "OperationHandle.await");
    assertViolations(
        controllersReturnNoMutableSetsRule(),
        controllers + "ReturnsMutableSet",
        "mutable()",
        "!lines()");
    assertViolations(
        controllersReadNoSystemPropertiesRule(), controllers + "ReadsPlatform", "getProperty");
    // Always on Top.
    assertViolations(
        reflectionOnlyInStackingClassesRule(),
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
          new Blocking(AtomicFiles.class, ".*"),
          new Blocking(SafePaths.class, "logPath|logFile|settingsDirectory"),
          new Blocking(ConfigStore.class, "load|save"),
          new Blocking(SessionStore.class, "load|loadWindows|save|saveWindows"),
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

  // Dialog colors.

  /**
   * A code unit that creates a {@code Shell} with an owning shell also calls {@code
   * DialogColors.prepare}, as {@code Dialogs.shell} does, so a dialog built another way still takes
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
    final String colors = ROOT + ".ui.swt.DialogColors";
    return codeUnits()
        .should(
            new ArchCondition<JavaCodeUnit>(
                "call DialogColors.prepare when creating an owned Shell") {
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
                      && call.getName().equals("prepare")) {
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
}
