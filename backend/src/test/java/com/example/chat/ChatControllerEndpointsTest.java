package com.example.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * FR-011: la API no ofrece ninguna operacion que devuelva, liste, edite o borre el historial ni los
 * identificadores de sesion. Si alguien anade una ruta, esta prueba falla y obliga a revisarlo.
 */
class ChatControllerEndpointsTest {

    /** Las rutas conocidas de la API completa. Cualquier otra es un cambio que hay que justificar. */
    private static final Set<String> KNOWN = Set.of(
            "POST /api/v1/chat",
            "POST /api/v1/classify",
            "GET /health/llm");

    /** "METODO ruta" de cada metodo mapeado de cada @RestController del paquete com.example.chat. */
    private static Set<String> mappedRoutes() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> routes = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.example.chat")) {
            Class<?> controller = ClassUtils.forName(definition.getBeanClassName(), ChatControllerEndpointsTest.class.getClassLoader());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = (base == null || base.path().length == 0) ? "" : base.path()[0];

            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = prefix + (mapping.path().length == 0 ? "" : mapping.path()[0]);
                RequestMethod[] methods = mapping.method().length == 0 ? RequestMethod.values() : mapping.method();
                for (RequestMethod httpMethod : methods) {
                    routes.add(httpMethod + " " + path);
                }
            }
        }
        return routes;
    }

    @Test
    void theApiExposesExactlyTheKnownRoutes() throws Exception {
        assertEquals(new TreeSet<>(KNOWN), mappedRoutes());
    }

    @Test
    void noRouteMentionsSessionsHistoryOrMemory() throws Exception {
        for (String route : mappedRoutes()) {
            String lower = route.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("session"), "ruta de sesiones: " + route);
            assertFalse(lower.contains("history"), "ruta de historial: " + route);
            assertFalse(lower.contains("memory"), "ruta de memoria: " + route);
        }
    }

    @Test
    void theChatPathOnlyAcceptsPost() throws Exception {
        for (String route : mappedRoutes()) {
            if (route.endsWith("/chat") || route.contains("/chat/")) {
                assertEquals("POST", route.substring(0, route.indexOf(' ')), "el chat solo acepta POST: " + route);
            }
        }
    }
}
