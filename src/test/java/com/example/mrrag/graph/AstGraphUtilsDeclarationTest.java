package com.example.mrrag.graph;

import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtLambda;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstGraphUtilsDeclarationTest {

    @Test
    void declarationOf_method_excludesBody() {
        String src = """
                package p;
                class Foo {
                  public int bar(String x) {
                    return 1;
                  }
                }
                """;
        CtModel model = buildModel(src, "p/Foo.java");
        CtMethod<?> m = model.getElements(new TypeFilter<>(CtMethod.class)).stream()
                .filter(x -> "bar".equals(x.getSimpleName()))
                .findFirst()
                .orElseThrow();
        String[] lines = src.split("\n", -1);
        String decl = AstGraphUtils.declarationOf(lines, m.getPosition());
        assertTrue(decl.contains("int bar"), decl);
        assertTrue(decl.contains("String x"), decl);
        assertFalse(decl.contains("return"), decl);
        assertFalse(decl.contains("{"), () -> decl);
    }

    @Test
    void declarationOf_field_usesCompoundOrLineRange() {
        String src = "package p;\nclass Foo {\n  private final java.util.List<String> names = null;\n}\n";
        CtModel model = buildModel(src, "p/Foo.java");
        CtField<?> f = model.getElements(new TypeFilter<>(CtField.class)).get(0);
        String[] lines = src.split("\n", -1);
        String decl = AstGraphUtils.declarationOf(lines, f.getPosition());
        assertTrue(decl.contains("List<String>"), decl);
        assertTrue(decl.contains("names"), decl);
    }

    @Test
    void declarationOf_lambda_signatureWithoutBlockContent() {
        String src = """
                package p;
                import java.util.function.Function;
                class Foo {
                  Function<String, Integer> f = (String s) -> { return s.length(); };
                }
                """;
        CtModel model = buildModel(src, "p/Foo.java");
        CtLambda<?> lambda = model.getElements(new TypeFilter<>(CtLambda.class)).get(0);
        String[] lines = src.split("\n", -1);
        String decl = AstGraphUtils.declarationOf(lines, lambda.getPosition());
        assertTrue(decl.contains("String s"), decl);
        assertTrue(decl.contains("->"), decl);
    }

    private static CtModel buildModel(String content, String path) {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(new VirtualFile(content, path));
        return launcher.buildModel();
    }
}
