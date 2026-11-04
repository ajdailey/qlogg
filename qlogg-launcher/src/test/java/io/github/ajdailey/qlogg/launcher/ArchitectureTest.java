package io.github.ajdailey.qlogg.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.FoldMap;
import io.github.ajdailey.qlogg.core.model.Highlighter;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.Viewport;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.beans.XMLDecoder;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
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
            ROOT + ".core.concurrent..")
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

  @Test
  void logbackOnlyInLauncher() {
    noClasses()
        .that()
        .resideOutsideOfPackage(LAUNCHER)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("ch.qos.logback..")
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

  /**
   * Checks a rule against one fixture class: every fragment appears in the failure report, and no
   * fragment prefixed {@code !} does.
   */
  private static void assertViolations(ArchRule rule, String fixture, String... fragments)
      throws ClassNotFoundException {
    JavaClasses imported = new ClassFileImporter().importClasses(Class.forName(fixture));
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
}
