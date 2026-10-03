package org.testwatch.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Parses only reports created or rewritten by the current invocation. */
final class SurefireReports {
    static final class Stamp {
        final FileTime modified;
        final byte[] digest;
        Stamp(Path file) throws IOException {
            modified = Files.getLastModifiedTime(file);
            try {
                digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        boolean same(Stamp other) {
            return other != null && modified.equals(other.modified) && Arrays.equals(digest, other.digest);
        }
    }

    static final class Results {
        final int[] counts = new int[4];
        final Set<String> failed = new LinkedHashSet<>();
        boolean found;
    }

    static Map<Path, Stamp> snapshot(Set<Path> directories) throws IOException {
        Map<Path, Stamp> files = new LinkedHashMap<>();
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) continue;
            try (var stream = Files.newDirectoryStream(directory, "TEST-*.xml")) {
                for (Path file : stream) files.put(file, new Stamp(file));
            }
        }
        return files;
    }

    static Results readFresh(Set<Path> directories, Map<Path, Stamp> before) throws Exception {
        Results result = new Results();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        for (Map.Entry<Path, Stamp> file : snapshot(directories).entrySet()) {
            if (file.getValue().same(before.get(file.getKey()))) continue;
            var parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            Element suite = parser.parse(file.getKey().toFile()).getDocumentElement();
            if (!"testsuite".equals(suite.getTagName())) throw new IOException("Unexpected report: " + file.getKey());
            String[] names = {"tests", "failures", "errors", "skipped"};
            for (int i = 0; i < names.length; i++) {
                String value = suite.getAttribute(names[i]);
                int count = value.isEmpty() ? 0 : Integer.parseInt(value);
                if (count < 0) throw new IOException("Negative count in " + file.getKey());
                result.counts[i] = Math.addExact(result.counts[i], count);
            }
            result.found = true;
            NodeList cases = suite.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element test = (Element) cases.item(i);
                for (Node child = test.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if ("failure".equals(child.getNodeName()) || "error".equals(child.getNodeName())) {
                        String name = test.getAttribute("classname");
                        if (name.isBlank()) name = suite.getAttribute("name");
                        int nested = name.indexOf('$');
                        if (nested >= 0) name = name.substring(0, nested);
                        if (!name.isBlank()) result.failed.add(name);
                    }
                }
            }
        }
        if (result.counts[1] + result.counts[2] + result.counts[3] > result.counts[0]) {
            throw new IOException("Invalid Surefire test counts");
        }
        return result;
    }
}
