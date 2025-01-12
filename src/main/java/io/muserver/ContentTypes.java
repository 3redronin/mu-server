package io.muserver;

import org.jspecify.annotations.NullMarked;

/**
 * String constants for content-types
 */
@NullMarked
public class ContentTypes {

    /**
     * Plain text: {@code "text/plain"}
     */
    public static final CharSequence TEXT_PLAIN = headerValue("text/plain");

    /**
     * Plain text: {@code "text/plain;charset=utf-8"}
     */
    public static final CharSequence TEXT_PLAIN_UTF8 = headerValue("text/plain;charset=utf-8");

    /**
     * Markdown: {@code "text/markdown"}
     */
    public static final CharSequence TEXT_MARKDOWN = headerValue("text/markdown");

    /**
     * Markdown: {@code "text/markdown;charset=utf-8"}
     */
    public static final CharSequence TEXT_MARKDOWN_UTF8 = headerValue("text/markdown;charset=utf-8");

    /**
     * AAC audio file:  {@code "audio/aac"}
     */
    public static final CharSequence AUDIO_AAC = headerValue("audio/aac");

    /**
     * AbiWord document:  {@code "application/x-abiword"}
     */
    public static final CharSequence APPLICATION_X_ABIWORD = headerValue("application/x-abiword");

    /**
     * AVI: Audio Video Interleave:  {@code "video/x-msvideo"}
     */
    public static final CharSequence VIDEO_X_MSVIDEO = headerValue("video/x-msvideo");

    /**
     * Amazon Kindle eBook format:  {@code "application/vnd.amazon.ebook"}
     */
    public static final CharSequence APPLICATION_VND_AMAZON_EBOOK = headerValue("application/vnd.amazon.ebook");

    /**
     * Any kind of binary data:  {@code "application/octet-stream"}
     */
    public static final CharSequence APPLICATION_OCTET_STREAM = headerValue("application/octet-stream");

    /**
     * BZip archive:  {@code "application/x-bzip"}
     */
    public static final CharSequence APPLICATION_X_BZIP = headerValue("application/x-bzip");

    /**
     * BZip2 archive:  {@code "application/x-bzip2"}
     */
    public static final CharSequence APPLICATION_X_BZIP2 = headerValue("application/x-bzip2");

    /**
     * C-Shell script:  {@code "application/x-csh"}
     */
    public static final CharSequence APPLICATION_X_CSH = headerValue("application/x-csh");

    /**
     * Cascading Style Sheets (CSS):  {@code "text/css"}
     */
    public static final CharSequence TEXT_CSS = headerValue("text/css");

    /**
     * Cascading Style Sheets (CSS):  {@code "text/css;charset=utf-8"}
     */
    public static final CharSequence TEXT_CSS_UTF8 = headerValue("text/css;charset=utf-8");

    /**
     * Comma-separated values (CSV):  {@code "text/csv"}
     */
    public static final CharSequence TEXT_CSV = headerValue("text/csv");

    /**
     * Comma-separated values (CSV):  {@code "text/csv;charset=utf-8"}
     */
    public static final CharSequence TEXT_CSV_UTF8 = headerValue("text/csv;charset=utf-8");

    /**
     * Microsoft Word:  {@code "application/msword"}
     */
    public static final CharSequence APPLICATION_MSWORD = headerValue("application/msword");

    /**
     * MS Embedded OpenType fonts:  {@code "application/vnd.ms-fontobject"}
     */
    public static final CharSequence APPLICATION_VND_MS_FONTOBJECT = headerValue("application/vnd.ms-fontobject");

    /**
     * Electronic publication (EPUB):  {@code "application/epub+zip"}
     */
    public static final CharSequence APPLICATION_EPUB_ZIP = headerValue("application/epub+zip");

    /**
     * GZIP (.gz):  {@code "application/gzip"}
     */
    public static final CharSequence APPLICATION_GZIP = headerValue("application/gzip");

    /**
     * Graphics Interchange Format (GIF):  {@code "image/gif"}
     */
    public static final CharSequence IMAGE_GIF = headerValue("image/gif");

    /**
     * HyperText Markup Language (HTML):  {@code "text/html"}
     */
    public static final CharSequence TEXT_HTML = headerValue("text/html");

    /**
     * HyperText Markup Language (HTML):  {@code "text/html;charset=utf-8"}
     */
    public static final CharSequence TEXT_HTML_UTF8 = headerValue("text/html;charset=utf-8");

    /**
     * Icon format:  {@code "image/x-icon"}
     */
    public static final CharSequence IMAGE_X_ICON = headerValue("image/x-icon");

    /**
     * iCalendar format:  {@code "text/calendar"}
     */
    public static final CharSequence TEXT_CALENDAR = headerValue("text/calendar");

    /**
     * iCalendar format:  {@code "text/calendar;charset=utf-8"}
     */
    public static final CharSequence TEXT_CALENDAR_UTF8 = headerValue("text/calendar;charset=utf-8");

    /**
     * Java Archive (JAR):  {@code "application/java-archive"}
     */
    public static final CharSequence APPLICATION_JAVA_ARCHIVE = headerValue("application/java-archive");

    /**
     * JPEG images:  {@code "image/jpeg"}
     */
    public static final CharSequence IMAGE_JPEG = headerValue("image/jpeg");

    /**
     * JavaScript (ECMAScript):  {@code "application/javascript"}
     */
    public static final CharSequence APPLICATION_JAVASCRIPT = headerValue("application/javascript");

    /**
     * JSON format:  {@code "application/json"}
     */
    public static final CharSequence APPLICATION_JSON = headerValue("application/json");

    /**
     * Musical Instrument Digital Interface (MIDI):  {@code "audio/midi"}
     */
    public static final CharSequence AUDIO_MIDI = headerValue("audio/midi");

    /**
     * MPEG Video:  {@code "video/mp4"}
     */
    public static final CharSequence VIDEO_MP4 = headerValue("video/mp4");

    /**
     * MPEG Video:  {@code "video/mpeg"}
     */
    public static final CharSequence VIDEO_MPEG = headerValue("video/mpeg");

    /**
     * Apple Installer Package:  {@code "application/vnd.apple.installer+xml"}
     */
    public static final CharSequence APPLICATION_VND_APPLE_INSTALLER_XML = headerValue("application/vnd.apple.installer+xml");

    /**
     * OpenDocument presentation document:  {@code "application/vnd.oasis.opendocument.presentation"}
     */
    public static final CharSequence APPLICATION_VND_OASIS_OPENDOCUMENT_PRESENTATION = headerValue("application/vnd.oasis.opendocument.presentation");

    /**
     * OpenDocument spreadsheet document:  {@code "application/vnd.oasis.opendocument.spreadsheet"}
     */
    public static final CharSequence APPLICATION_VND_OASIS_OPENDOCUMENT_SPREADSHEET = headerValue("application/vnd.oasis.opendocument.spreadsheet");

    /**
     * OpenDocument text document:  {@code "application/vnd.oasis.opendocument.text"}
     */
    public static final CharSequence APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT = headerValue("application/vnd.oasis.opendocument.text");

    /**
     * OGG audio:  {@code "audio/ogg"}
     */
    public static final CharSequence AUDIO_OGG = headerValue("audio/ogg");

    /**
     * OGG video:  {@code "video/ogg"}
     */
    public static final CharSequence VIDEO_OGG = headerValue("video/ogg");

    /**
     * OGG:  {@code "application/ogg"}
     */
    public static final CharSequence APPLICATION_OGG = headerValue("application/ogg");

    /**
     * OpenType font:  {@code "font/otf"}
     */
    public static final CharSequence FONT_OTF = headerValue("font/otf");

    /**
     * Portable Network Graphics:  {@code "image/png"}
     */
    public static final CharSequence IMAGE_PNG = headerValue("image/png");

    /**
     * Adobe Portable Document Format (PDF):  {@code "application/pdf"}
     */
    public static final CharSequence APPLICATION_PDF = headerValue("application/pdf");

    /**
     * Microsoft PowerPoint:  {@code "application/vnd.ms-powerpoint"}
     */
    public static final CharSequence APPLICATION_VND_MS_POWERPOINT = headerValue("application/vnd.ms-powerpoint");

    /**
     * RAR archive:  {@code "application/x-rar-compressed"}
     */
    public static final CharSequence APPLICATION_X_RAR_COMPRESSED = headerValue("application/x-rar-compressed");

    /**
     * Rich Text Format (RTF):  {@code "application/rtf"}
     */
    public static final CharSequence APPLICATION_RTF = headerValue("application/rtf");

    /**
     * Bourne shell script:  {@code "application/x-sh"}
     */
    public static final CharSequence APPLICATION_X_SH = headerValue("application/x-sh");

    /**
     * Scalable Vector Graphics (SVG):  {@code "image/svg+xml"}
     */
    public static final CharSequence IMAGE_SVG_XML = headerValue("image/svg+xml");

    /**
     * Small web format (SWF) or Adobe Flash document:  {@code "application/x-shockwave-flash"}
     */
    public static final CharSequence APPLICATION_X_SHOCKWAVE_FLASH = headerValue("application/x-shockwave-flash");

    /**
     * Tape Archive (TAR):  {@code "application/x-tar"}
     */
    public static final CharSequence APPLICATION_X_TAR = headerValue("application/x-tar");

    /**
     * Tagged Image File Format (TIFF):  {@code "image/tiff"}
     */
    public static final CharSequence IMAGE_TIFF = headerValue("image/tiff");

    /**
     * Typescript file:  {@code "application/typescript"}
     */
    public static final CharSequence APPLICATION_TYPESCRIPT = headerValue("application/typescript");

    /**
     * TrueType Font:  {@code "font/ttf"}
     */
    public static final CharSequence FONT_TTF = headerValue("font/ttf");

    /**
     * Microsoft Visio:  {@code "application/vnd.visio"}
     */
    public static final CharSequence APPLICATION_VND_VISIO = headerValue("application/vnd.visio");

    /**
     * Waveform Audio Format:  {@code "audio/x-wav"}
     */
    public static final CharSequence AUDIO_X_WAV = headerValue("audio/x-wav");

    /**
     * WEBM audio:  {@code "audio/webm"}
     */
    public static final CharSequence AUDIO_WEBM = headerValue("audio/webm");

    /**
     * WEBM video:  {@code "video/webm"}
     */
    public static final CharSequence VIDEO_WEBM = headerValue("video/webm");

    /**
     * WEBP image:  {@code "image/webp"}
     */
    public static final CharSequence IMAGE_WEBP = headerValue("image/webp");

    /**
     * AVIF image:  {@code "image/avif"}
     */
    public static final CharSequence IMAGE_AVIF = headerValue("image/avif");

    /**
     * Web Open Font Format (WOFF):  {@code "font/woff"}
     */
    public static final CharSequence FONT_WOFF = headerValue("font/woff");

    /**
     * Web Open Font Format (WOFF):  {@code "font/woff2"}
     */
    public static final CharSequence FONT_WOFF2 = headerValue("font/woff2");

    /**
     * XHTML:  {@code "application/xhtml+xml"}
     */
    public static final CharSequence APPLICATION_XHTML_XML = headerValue("application/xhtml+xml");

    /**
     * Microsoft Excel:  {@code "application/vnd.ms-excel"}
     */
    public static final CharSequence APPLICATION_VND_MS_EXCEL = headerValue("application/vnd.ms-excel");

    /**
     * XML:  {@code "application/xml"}
     */
    public static final CharSequence APPLICATION_XML = headerValue("application/xml");

    /**
     * XUL:  {@code "application/vnd.mozilla.xul+xml"}
     */
    public static final CharSequence APPLICATION_VND_MOZILLA_XUL_XML = headerValue("application/vnd.mozilla.xul+xml");

    /**
     * ZIP archive:  {@code "application/zip"}
     */
    public static final CharSequence APPLICATION_ZIP = headerValue("application/zip");

    /**
     * 3GPP audio/video container:  {@code "video/3gpp"}
     */
    public static final CharSequence VIDEO_3GPP = headerValue("video/3gpp");

    /**
     * 3GPP2 audio/video container:  {@code "video/3gpp2"}
     */
    public static final CharSequence VIDEO_3GPP2 = headerValue("video/3gpp2");

    /**
     * 7-zip archive:  {@code "application/x-7z-compressed"}
     */
    public static final CharSequence APPLICATION_X_7Z_COMPRESSED = headerValue("application/x-7z-compressed");

    /**
     * Server-Sent-Events, a.k.a. SSE, a.k.a. Event Streams: {@code "text/event-stream"}
     */
    public static final CharSequence TEXT_EVENT_STREAM = headerValue("text/event-stream");

    /**
     * MKV video files {@code "video/x-matroska"}
     */
    public static final CharSequence VIDEO_X_MATROSKA = headerValue("video/x-matroska");

    /**
     * Web App Manifest files {@code "application/manifest+json"}
     */
    public static final CharSequence WEB_APP_MANIFEST = headerValue("application/manifest+json");

    private ContentTypes() {}

    private static HeaderString headerValue(String contentType) {
        return HeaderString.valueOf(contentType, HeaderString.Type.VALUE);
    }

}
