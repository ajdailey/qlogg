package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link MainWindowSlot} forwards every {@link MainView} method to its view with the same arguments
 * and returns the view's result, by reflection over the interface, so a method added to {@code
 * MainView} is checked as soon as the slot compiles. {@code setActive} and {@code setIdle} stay in
 * the slot's busy count.
 */
class MainWindowSlotTest {
  /** Records each call and answers a marker value of the method's return type. */
  private static final class Recorder implements InvocationHandler {
    final List<String> calls = new ArrayList<String>();
    final List<Object[]> arguments = new ArrayList<Object[]>();

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      calls.add(method.getName());
      arguments.add(args == null ? new Object[0] : args);
      return marker(method.getReturnType());
    }
  }

  private static final UiDispatcher NOW =
      new UiDispatcher() {
        @Override
        public void asyncExec(Runnable task) {
          task.run();
        }

        @Override
        public void timerExec(int delayMillis, Runnable task) {
          task.run();
        }

        @Override
        public boolean isUiThread() {
          return true;
        }
      };

  @Test
  void everyMainViewMethodReachesTheView() throws IllegalAccessException {
    Recorder recorder = new Recorder();
    MainView view =
        (MainView)
            Proxy.newProxyInstance(
                MainView.class.getClassLoader(), new Class<?>[] {MainView.class}, recorder);
    MainWindowSlot slot = new MainWindowSlot(view, NOW);
    int checked = 0;
    for (Method method : MainView.class.getMethods()) {
      if (method.isDefault() || Modifier.isStatic(method.getModifiers())) {
        continue;
      }
      Object[] args = new Object[method.getParameterCount()];
      for (int i = 0; i < args.length; i++) {
        args[i] = marker(method.getParameterTypes()[i]);
      }
      int before = recorder.calls.size();
      Object result;
      try {
        result = method.invoke(slot, args);
      } catch (InvocationTargetException e) {
        throw new AssertionError(method.getName(), e.getCause());
      }
      String name = method.getName();
      if (name.equals("setActive") || name.equals("setIdle")) {
        assertEquals(before, recorder.calls.size(), name + " reached the view");
        continue;
      }
      assertEquals(before + 1, recorder.calls.size(), name);
      assertEquals(name, recorder.calls.get(before));
      Object[] passed = recorder.arguments.get(before);
      for (int i = 0; i < args.length; i++) {
        assertSame(args[i], passed[i], name + " argument " + i);
      }
      assertEquals(marker(method.getReturnType()), result, name + " result");
      checked++;
    }
    assertTrue(checked >= 50, "methods checked: " + checked);
  }

  @Test
  void theBusyCountStaysInTheSlot() {
    MainWindowSlot slot = new MainWindowSlot((MainView) marker(MainView.class), NOW);
    slot.setActive();
    slot.setActive();
    slot.setIdle();
    assertEquals(1, slot.busy().active());
    slot.setIdle();
    slot.setIdle();
    assertEquals(0, slot.busy().active());
  }

  private static final Path PATH = Path.of("mock.log");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final LocalDate DATE = LocalDate.of(2026, 1, 1);
  private static final List<Object> LIST = new ArrayList<Object>();
  private static final WindowGeometry.Request REQUEST = new WindowGeometry.Request(1, 2, 3, 4);
  private static final Map<Class<?>, Object> PROXIES = new HashMap<Class<?>, Object>();

  /**
   * A value of a type that a forwarded call must pass through unchanged; null for a class the test
   * does not construct.
   */
  private static Object marker(Class<?> type) {
    if (type == void.class) {
      return null;
    } else if (type == boolean.class) {
      return Boolean.TRUE;
    } else if (type == int.class) {
      return 7;
    } else if (type == long.class) {
      return 7L;
    } else if (type == String.class) {
      return "mock";
    } else if (type.isEnum()) {
      return type.getEnumConstants()[0];
    } else if (type == Path.class) {
      return PATH;
    } else if (type == ZoneId.class) {
      return ZONE;
    } else if (type == LocalDate.class) {
      return DATE;
    } else if (type == List.class) {
      return LIST;
    } else if (type == WindowGeometry.Request.class) {
      return REQUEST;
    } else if (type.isInterface()) {
      Object proxy = PROXIES.get(type);
      if (proxy == null) {
        proxy =
            Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new Recorder());
        PROXIES.put(type, proxy);
      }
      return proxy;
    }
    return null;
  }
}
