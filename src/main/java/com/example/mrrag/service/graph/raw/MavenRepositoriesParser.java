package com.example.mrrag.service.graph.raw;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads {@code <repositories>} / {@code <pluginRepositories>} {@code <url>} values from
 * {@code pom.xml}. Maven Central is always included.
 */
public final class MavenRepositoriesParser {

    private MavenRepositoriesParser() {
    }

    public static List<String> collect(Path pomDir) {
        Path pom = pomDir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return List.of(GradleRepositoriesParser.MAVEN_CENTRAL);
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(GradleRepositoriesParser.MAVEN_CENTRAL);
        try (InputStream in = Files.newInputStream(pom)) {
            collectUrls(in, out);
        } catch (Exception e) {
            return List.of(GradleRepositoriesParser.MAVEN_CENTRAL);
        }
        return List.copyOf(out);
    }

    private static void collectUrls(InputStream in, Set<String> out) throws Exception {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XMLStreamReader r = f.createXMLStreamReader(in);
        List<String> stack = new ArrayList<>();

        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String name = r.getLocalName();
                if ("url".equals(name)
                        && (hasAncestor(stack, "repository") || hasAncestor(stack, "pluginRepository"))) {
                    String url = r.getElementText().trim();
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        out.add(GradleRepositoriesParser.normalizeRepoBase(url));
                    }
                } else {
                    stack.add(name);
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            }
        }
        r.close();
    }

    private static boolean hasAncestor(List<String> stack, String ancestor) {
        for (String s : stack) {
            if (ancestor.equals(s)) return true;
        }
        return false;
    }
}
