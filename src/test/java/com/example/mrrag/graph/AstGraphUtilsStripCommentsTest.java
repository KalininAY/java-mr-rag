package com.example.mrrag.graph;

import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstGraphUtilsStripCommentsTest {

    @Test
    void strip_removesJavadocAndLineComment() {
        String s = "  /** hello */\n  void m();";
        String out = AstGraphUtils.stripJavaCommentsAndJavadoc(s).replaceAll("\\s+", " ").trim();
        assertEquals("void m();", out);
    }

    @Test
    void strip_preservesDoubleSlashInsideString() {
        String s = "String x = \"// not a comment\";";
        assertEquals(s, AstGraphUtils.stripJavaCommentsAndJavadoc(s));
    }

    @Test
    void strip_preservesBlockInsideString() {
        String s = "String x = \"/* fake */\";";
        assertEquals(s, AstGraphUtils.stripJavaCommentsAndJavadoc(s));
    }

    @Test
    void declarationOf_stripsJavadocBeforeMethod() {
        String src = """
                package p;
                class Foo {
                  /** API doc */
                  public int bar() {
                    return 0;
                  }
                }
                """;
        var lines = src.split("\n", -1);
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(new VirtualFile(src, "p/Foo.java"));
        CtModel model = launcher.buildModel();
        CtMethod<?> m = model.getElements(new TypeFilter<>(CtMethod.class)).stream()
                .filter(x -> "bar".equals(x.getSimpleName()))
                .findFirst()
                .orElseThrow();
        String decl = AstGraphUtils.declarationOf(lines, m.getPosition());
        assertFalse(decl.contains("API doc"), decl);
        assertTrue(decl.contains("int bar"), decl);
    }
}
