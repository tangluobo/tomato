package com.tangluobo.tomato.module.connect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownResourcePathTest {

    @Test
    void resolvesSiblingAndParentImagesForObjectStorage() {
        assertEquals("docs/images/demo.png", MarkdownEditorPane.resolveRemoteResourcePath(
                "docs/readme.md", "images/demo.png", false));
        assertEquals("shared/logo.png", MarkdownEditorPane.resolveRemoteResourcePath(
                "docs/guide/readme.md", "../../shared/logo.png", false));
    }

    @Test
    void preservesSftpRootAndDecodesFileNames() {
        assertEquals("/srv/docs/images/demo image.png", MarkdownEditorPane.resolveRemoteResourcePath(
                "/srv/docs/readme.md", "./images/demo%20image.png?raw=1", true));
        assertEquals("/assets/logo.png", MarkdownEditorPane.resolveRemoteResourcePath(
                "/srv/docs/readme.md", "/assets/logo.png", true));
    }
}
