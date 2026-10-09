package com.offerpilot;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.auth.application.StaffUserAdminService;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Structural guard for app-level authentication while SecurityConfig permits /api/v1/**. */
class ControllerIdentityArchitectureTest {
    private static final Set<String> PUBLIC_AUTH = Set.of(
            "POST /api/v1/auth/login", "POST /api/v1/auth/register",
            "POST /api/v1/auth/guest", "POST /api/v1/auth/staff/login", "POST /api/v1/auth/demo-login");
    private final Map<Class<?>, GateSource> sources = new HashMap<>();

    @Test
    void everyApiHandlerInvokesAnIdentityGate() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var mappings = new MappingReader();
        List<String> failures = new ArrayList<>();
        Set<String> seenPublic = new HashSet<>();
        int protectedHandlers = 0;
        for (var bean : scanner.findCandidateComponents("com.offerpilot")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                RequestMappingInfo mapping = mappings.read(method, type);
                if (mapping == null) continue;
                for (String path : mapping.getPatternValues()) {
                    if (!path.startsWith("/api/v1/")) continue;
                    var verbs = mapping.getMethodsCondition().getMethods();
                    boolean publicAuth = !verbs.isEmpty() && verbs.stream()
                            .allMatch(verb -> PUBLIC_AUTH.contains(verb + " " + path));
                    if (publicAuth) {
                        verbs.forEach(verb -> seenPublic.add(verb + " " + path));
                        continue;
                    }
                    protectedHandlers++;
                    // /auth/verify authenticates its supplied credential via PasswordLoginService.verify.
                    boolean tokenVerification = path.equals("/api/v1/auth/verify")
                            && verbs.equals(Set.of(org.springframework.web.bind.annotation.RequestMethod.GET));
                    if (!source(type).gated(key(method), new HashSet<>(), tokenVerification)) {
                        failures.add(type.getSimpleName() + "." + method.getName() + " " + path);
                    }
                }
            }
        }
        assertThat(seenPublic).containsExactlyInAnyOrderElementsOf(PUBLIC_AUTH);
        assertThat(protectedHandlers).isPositive();
        assertThat(failures).as("Handlers must invoke CurrentUser.require or a verified staff gate").isEmpty();
    }

    @Test
    void rejectsNakedHandlerAndUnusedOrCommentOnlyGate() throws IOException {
        var naked = new GateSource("class Naked { String list() { return \"open\"; } }", Map.of());
        assertThat(naked.gated("list#0", new HashSet<>(), false)).isFalse();
        var unused = new GateSource("""
                class Naked {
                    CurrentUser users;
                    String owner() { return users.require("Bearer credential"); }
                    String list() { /* users.require("credential"); */ return "users.require(fake)"; }
                }
                """, Map.of("users", CurrentUser.class));
        assertThat(unused.gated("list#0", new HashSet<>(), false)).isFalse();
    }

    @Test
    void removingRequireFromOwnerHelperIsDetected() throws IOException {
        String guarded = """
                class Protected {
                    CurrentUser users;
                    String owner() { return users.require("Bearer credential"); }
                    String list() { return owner(); }
                }
                """;
        assertThat(new GateSource(guarded, Map.of("users", CurrentUser.class))
                .gated("list#0", new HashSet<>(), false)).isTrue();
        assertThat(new GateSource(guarded.replace("users.require(\"Bearer credential\")", "\"anonymous\""),
                Map.of("users", CurrentUser.class)).gated("list#0", new HashSet<>(), false)).isFalse();
    }

    @Test
    void staffDelegateMustActuallyRequireStaffIdentity() throws Exception {
        var staff = source(StaffUserAdminService.class);
        assertThat(staff.gated("listUsers#4", new HashSet<>(), false)).isTrue();
        assertThat(staff.gated("setUserStatus#3", new HashSet<>(), false)).isTrue();
        var removed = new GateSource(readSource(StaffUserAdminService.class)
                .replace("logins.requireStaffId(authorization);", ""), Map.of("logins", PasswordLoginService.class));
        assertThat(removed.gated("listUsers#4", new HashSet<>(), false)).isFalse();
        assertThat(removed.gated("setUserStatus#3", new HashSet<>(), false)).isFalse();
    }

    private GateSource source(Class<?> type) throws IOException {
        if (!sources.containsKey(type)) {
            Map<String, Class<?>> fields = new HashMap<>();
            for (var field : type.getDeclaredFields()) fields.put(field.getName(), field.getType());
            sources.put(type, new GateSource(readSource(type), fields));
        }
        return sources.get(type);
    }

    private static String readSource(Class<?> type) throws IOException {
        Path backend = Path.of("").toAbsolutePath();
        while (backend != null && !Files.isDirectory(backend.resolve("offerpilot-bootstrap"))) backend = backend.getParent();
        if (backend == null) throw new IOException("Cannot locate backend reactor source root");
        String relative = "src/main/java/" + type.getName().replace('.', '/') + ".java";
        try (var modules = Files.list(backend)) {
            List<Path> matches = modules.map(module -> module.resolve(relative)).filter(Files::isRegularFile).toList();
            assertThat(matches).as("Source for %s", type.getName()).hasSize(1);
            return Files.readString(matches.get(0));
        }
    }

    private static String key(Method method) { return method.getName() + "#" + method.getParameterCount(); }

    private static class MappingReader extends RequestMappingHandlerMapping {
        RequestMappingInfo read(Method method, Class<?> type) { return getMappingForMethod(method, type); }
    }

    /** AST call graph follows local helpers and typed staff delegates; comments/strings cannot satisfy it.
     * This structural check does not prove control-flow dominance; HTTP auth tests remain necessary.
     */
    private class GateSource {
        private final Map<String, MethodTree> methods = new HashMap<>();
        private final Map<String, Class<?>> fields;

        GateSource(String text, Map<String, Class<?>> fields) throws IOException {
            this.fields = fields;
            var compiler = ToolProvider.getSystemJavaCompiler();
            assertThat(compiler).as("Architecture guard requires a JDK").isNotNull();
            var file = new SimpleJavaFileObject(URI.create("string:///Controller.java"),
                    javax.tools.JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return text; }
            };
            try (var manager = compiler.getStandardFileManager(null, null, null)) {
                var task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, List.of(file));
                for (var unit : task.parse()) {
                    for (var declaration : unit.getTypeDecls()) {
                        if (!(declaration instanceof ClassTree type)) continue;
                        for (var member : type.getMembers()) {
                            if (member instanceof MethodTree method) {
                                String key = method.getName() + "#" + method.getParameters().size();
                                assertThat(methods.put(key, method)).as("Ambiguous method %s", key).isNull();
                            }
                        }
                    }
                }
            }
        }

        boolean gated(String key, Set<String> visited, boolean allowVerify) {
            if (!visited.add(key)) return false;
            MethodTree method = methods.get(key);
            assertThat(method).as("Source method %s", key).isNotNull();
            List<MethodInvocationTree> calls = new ArrayList<>();
            new TreeScanner<Void, Void>() {
                @Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                    calls.add(call);
                    return super.visitMethodInvocation(call, unused);
                }
            }.scan(method.getBody(), null);
            for (var call : calls) {
                ExpressionTree select = call.getMethodSelect();
                String name;
                if (select instanceof MemberSelectTree member) {
                    name = member.getIdentifier().toString();
                    String receiver = member.getExpression().toString().replaceFirst("^this\\.", "");
                    Class<?> field = fields.get(receiver);
                    if (field == CurrentUser.class && name.equals("require")) return true;
                    if (field == PasswordLoginService.class && (name.equals("requireStaffId")
                            || (allowVerify && name.equals("verify")))) return true;
                    if (field == StaffUserAdminService.class) {
                        try {
                            if (source(field).gated(name + "#" + call.getArguments().size(), new HashSet<>(), false)) return true;
                        } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
                    }
                    if (!receiver.equals("this")) continue;
                } else if (select instanceof IdentifierTree identifier) {
                    name = identifier.getName().toString();
                } else continue;
                String helper = name + "#" + call.getArguments().size();
                if (methods.containsKey(helper) && gated(helper, visited, allowVerify)) return true;
            }
            return false;
        }
    }
}
