package ge.magti.portal.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ASVS V4.1.1 for the one kind of text the portal serves but did not write:
 * an uploaded .txt. It went out as bare text/plain, leaving the encoding to
 * the browser's guess. FileTypeVerifier admits text that carries a UTF-16
 * byte-order mark or no NUL byte at all, so the label follows the bytes.
 */
class UploadedFileContentTypeTest {

    @Test
    void utf8TextSaysSo() {
        byte[] georgian = "ინსტრუქცია: გადატვირთეთ როუტერი".getBytes(StandardCharsets.UTF_8);

        assertEquals("text/plain;charset=UTF-8", UploadedFileController.servedType("text/plain", georgian).toString());
    }

    @Test
    void utf16TextIsNamedByItsByteOrderMark() {
        byte[] bigEndian = "﻿ტექსტი".getBytes(StandardCharsets.UTF_16BE);
        byte[] littleEndian = "﻿ტექსტი".getBytes(StandardCharsets.UTF_16LE);

        assertEquals("text/plain;charset=UTF-16", UploadedFileController.servedType("text/plain", bigEndian).toString());
        assertEquals("text/plain;charset=UTF-16", UploadedFileController.servedType("text/plain", littleEndian).toString());
    }

    /** Bytes that are not UTF-8 get a label every byte sequence satisfies, not one they contradict. */
    @Test
    void anythingElseIsLatin1RatherThanAGuess() {
        byte[] windows1252 = {'c', 'a', 'f', (byte) 0xE9};

        assertEquals("text/plain;charset=ISO-8859-1", UploadedFileController.servedType("text/plain", windows1252).toString());
    }

    @Test
    void binaryTypesAreLeftAsTheyAre() {
        assertEquals("image/png", UploadedFileController.servedType("image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'}).toString());
        assertEquals("application/octet-stream", UploadedFileController.servedType("not a type", new byte[0]).toString());
    }
}
