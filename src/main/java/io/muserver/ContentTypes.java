package io.muserver;

/**
 * String constants for content-types
 */
public class ContentTypes {

    /**
     * Plain text: {@code "text/plain"}
     */
    public static final CharSequence TEXT_PLAIN = HeaderString.valueOf("text/plain");

    /**
     * Plain text: {@code "text/plain;charset=utf-8"}
     */
    public static final CharSequence TEXT_PLAIN_UTF8 = HeaderString.valueOf("text/plain;charset=utf-8");

    /**
     * Markdown: {@code "text/markdown"}
     */
    public static final CharSequence TEXT_MARKDOWN = HeaderString.valueOf("text/markdown");

    /**
     * Markdown: {@code "text/markdown;charset=utf-8"}
     */
    public static final CharSequence TEXT_MARKDOWN_UTF8 = HeaderString.valueOf("text/markdown;charset=utf-8");

    /**
     * AAC audio file:  {@code "audio/aac"}
     */
    public static final CharSequence AUDIO_AAC = HeaderString.valueOf("audio/aac");

    /**
     * AbiWord document:  {@code "application/x-abiword"}
     */
    public static final CharSequence APPLICATION_X_ABIWORD = HeaderString.valueOf("application/x-abiword");

    /**
     * AVI: Audio Video Interleave:  {@code "video/x-msvideo"}
     */
    public static final CharSequence VIDEO_X_MSVIDEO = HeaderString.valueOf("video/x-msvideo");

    /**
     * Amazon Kindle eBook format:  {@code "application/vnd.amazon.ebook"}
     */
    public static final CharSequence APPLICATION_VND_AMAZON_EBOOK = HeaderString.valueOf("application/vnd.amazon.ebook");

    /**
     * Any kind of binary data:  {@code "application/octet-stream"}
     */
    public static final CharSequence APPLICATION_OCTET_STREAM = HeaderString.valueOf("application/octet-stream");

    /**
     * BZip archive:  {@code "application/x-bzip"}
     */
    public static final CharSequence APPLICATION_X_BZIP = HeaderString.valueOf("application/x-bzip");

    /**
     * BZip2 archive:  {@code "application/x-bzip2"}
     */
    public static final CharSequence APPLICATION_X_BZIP2 = HeaderString.valueOf("application/x-bzip2");

    /**
     * C-Shell script:  {@code "application/x-csh"}
     */
    public static final CharSequence APPLICATION_X_CSH = HeaderString.valueOf("application/x-csh");

    /**
     * Cascading Style Sheets (CSS):  {@code "text/css"}
     */
    public static final CharSequence TEXT_CSS = HeaderString.valueOf("text/css");

    /**
     * Cascading Style Sheets (CSS):  {@code "text/css;charset=utf-8"}
     */
    public static final CharSequence TEXT_CSS_UTF8 = HeaderString.valueOf("text/css;charset=utf-8");

    /**
     * Comma-separated values (CSV):  {@code "text/csv"}
     */
    public static final CharSequence TEXT_CSV = HeaderString.valueOf("text/csv");

    /**
     * Comma-separated values (CSV):  {@code "text/csv;charset=utf-8"}
     */
    public static final CharSequence TEXT_CSV_UTF8 = HeaderString.valueOf("text/csv;charset=utf-8");

    /**
     * Microsoft Word:  {@code "application/msword"}
     */
    public static final CharSequence APPLICATION_MSWORD = HeaderString.valueOf("application/msword");

    /**
     * MS Embedded OpenType fonts:  {@code "application/vnd.ms-fontobject"}
     */
    public static final CharSequence APPLICATION_VND_MS_FONTOBJECT = HeaderString.valueOf("application/vnd.ms-fontobject");

    /**
     * Electronic publication (EPUB):  {@code "application/epub+zip"}
     */
    public static final CharSequence APPLICATION_EPUB_ZIP = HeaderString.valueOf("application/epub+zip");

    /**
     * GZIP (.gz):  {@code "application/gzip"}
     */
    public static final CharSequence APPLICATION_GZIP = HeaderString.valueOf("application/gzip");

    /**
     * Graphics Interchange Format (GIF):  {@code "image/gif"}
     */
    public static final CharSequence IMAGE_GIF = HeaderString.valueOf("image/gif");

    /**
     * HyperText Markup Language (HTML):  {@code "text/html"}
     */
    public static final CharSequence TEXT_HTML = HeaderString.valueOf("text/html");

    /**
     * HyperText Markup Language (HTML):  {@code "text/html;charset=utf-8"}
     */
    public static final CharSequence TEXT_HTML_UTF8 = HeaderString.valueOf("text/html;charset=utf-8");

    /**
     * Icon format:  {@code "image/x-icon"}
     */
    public static final CharSequence IMAGE_X_ICON = HeaderString.valueOf("image/x-icon");

    /**
     * iCalendar format:  {@code "text/calendar"}
     */
    public static final CharSequence TEXT_CALENDAR = HeaderString.valueOf("text/calendar");

    /**
     * iCalendar format:  {@code "text/calendar;charset=utf-8"}
     */
    public static final CharSequence TEXT_CALENDAR_UTF8 = HeaderString.valueOf("text/calendar;charset=utf-8");

    /**
     * Java Archive (JAR):  {@code "application/java-archive"}
     */
    public static final CharSequence APPLICATION_JAVA_ARCHIVE = HeaderString.valueOf("application/java-archive");

    /**
     * JPEG images:  {@code "image/jpeg"}
     */
    public static final CharSequence IMAGE_JPEG = HeaderString.valueOf("image/jpeg");

    /**
     * JavaScript (ECMAScript):  {@code "application/javascript"}
     */
    public static final CharSequence APPLICATION_JAVASCRIPT = HeaderString.valueOf("application/javascript");

    /**
     * JSON format:  {@code "application/json"}
     */
    public static final CharSequence APPLICATION_JSON = HeaderString.valueOf("application/json");

    /**
     * Musical Instrument Digital Interface (MIDI):  {@code "audio/midi"}
     */
    public static final CharSequence AUDIO_MIDI = HeaderString.valueOf("audio/midi");

    /**
     * MPEG Video:  {@code "video/mp4"}
     */
    public static final CharSequence VIDEO_MP4 = HeaderString.valueOf("video/mp4");

    /**
     * MPEG Video:  {@code "video/mpeg"}
     */
    public static final CharSequence VIDEO_MPEG = HeaderString.valueOf("video/mpeg");

    /**
     * Apple Installer Package:  {@code "application/vnd.apple.installer+xml"}
     */
    public static final CharSequence APPLICATION_VND_APPLE_INSTALLER_XML = HeaderString.valueOf("application/vnd.apple.installer+xml");

    /**
     * OpenDocument presentation document:  {@code "application/vnd.oasis.opendocument.presentation"}
     */
    public static final CharSequence APPLICATION_VND_OASIS_OPENDOCUMENT_PRESENTATION = HeaderString.valueOf("application/vnd.oasis.opendocument.presentation");

    /**
     * OpenDocument spreadsheet document:  {@code "application/vnd.oasis.opendocument.spreadsheet"}
     */
    public static final CharSequence APPLICATION_VND_OASIS_OPENDOCUMENT_SPREADSHEET = HeaderString.valueOf("application/vnd.oasis.opendocument.spreadsheet");

    /**
     * OpenDocument text document:  {@code "application/vnd.oasis.opendocument.text"}
     */
    public static final CharSequence APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT = HeaderString.valueOf("application/vnd.oasis.opendocument.text");

    /**
     * OGG audio:  {@code "audio/ogg"}
     */
    public static final CharSequence AUDIO_OGG = HeaderString.valueOf("audio/ogg");

    /**
     * OGG video:  {@code "video/ogg"}
     */
    public static final CharSequence VIDEO_OGG = HeaderString.valueOf("video/ogg");

    /**
     * OGG:  {@code "application/ogg"}
     */
    public static final CharSequence APPLICATION_OGG = HeaderString.valueOf("application/ogg");

    /**
     * OpenType font:  {@code "font/otf"}
     */
    public static final CharSequence FONT_OTF = HeaderString.valueOf("font/otf");

    /**
     * Portable Network Graphics:  {@code "image/png"}
     */
    public static final CharSequence IMAGE_PNG = HeaderString.valueOf("image/png");

    /**
     * Adobe Portable Document Format (PDF):  {@code "application/pdf"}
     */
    public static final CharSequence APPLICATION_PDF = HeaderString.valueOf("application/pdf");

    /**
     * Microsoft PowerPoint:  {@code "application/vnd.ms-powerpoint"}
     */
    public static final CharSequence APPLICATION_VND_MS_POWERPOINT = HeaderString.valueOf("application/vnd.ms-powerpoint");

    /**
     * RAR archive:  {@code "application/x-rar-compressed"}
     */
    public static final CharSequence APPLICATION_X_RAR_COMPRESSED = HeaderString.valueOf("application/x-rar-compressed");

    /**
     * Rich Text Format (RTF):  {@code "application/rtf"}
     */
    public static final CharSequence APPLICATION_RTF = HeaderString.valueOf("application/rtf");

    /**
     * Bourne shell script:  {@code "application/x-sh"}
     */
    public static final CharSequence APPLICATION_X_SH = HeaderString.valueOf("application/x-sh");

    /**
     * Scalable Vector Graphics (SVG):  {@code "image/svg+xml"}
     */
    public static final CharSequence IMAGE_SVG_XML = HeaderString.valueOf("image/svg+xml");

    /**
     * Small web format (SWF) or Adobe Flash document:  {@code "application/x-shockwave-flash"}
     */
    public static final CharSequence APPLICATION_X_SHOCKWAVE_FLASH = HeaderString.valueOf("application/x-shockwave-flash");

    /**
     * Tape Archive (TAR):  {@code "application/x-tar"}
     */
    public static final CharSequence APPLICATION_X_TAR = HeaderString.valueOf("application/x-tar");

    /**
     * Tagged Image File Format (TIFF):  {@code "image/tiff"}
     */
    public static final CharSequence IMAGE_TIFF = HeaderString.valueOf("image/tiff");

    /**
     * Typescript file:  {@code "application/typescript"}
     */
    public static final CharSequence APPLICATION_TYPESCRIPT = HeaderString.valueOf("application/typescript");

    /**
     * TrueType Font:  {@code "font/ttf"}
     */
    public static final CharSequence FONT_TTF = HeaderString.valueOf("font/ttf");

    /**
     * Microsoft Visio:  {@code "application/vnd.visio"}
     */
    public static final CharSequence APPLICATION_VND_VISIO = HeaderString.valueOf("application/vnd.visio");

    /**
     * Waveform Audio Format:  {@code "audio/x-wav"}
     */
    public static final CharSequence AUDIO_X_WAV = HeaderString.valueOf("audio/x-wav");

    /**
     * WEBM audio:  {@code "audio/webm"}
     */
    public static final CharSequence AUDIO_WEBM = HeaderString.valueOf("audio/webm");

    /**
     * WEBM video:  {@code "video/webm"}
     */
    public static final CharSequence VIDEO_WEBM = HeaderString.valueOf("video/webm");

    /**
     * WEBP image:  {@code "image/webp"}
     */
    public static final CharSequence IMAGE_WEBP = HeaderString.valueOf("image/webp");

    /**
     * AVIF image:  {@code "image/avif"}
     */
    public static final CharSequence IMAGE_AVIF = HeaderString.valueOf("image/avif");

    /**
     * Web Open Font Format (WOFF):  {@code "font/woff"}
     */
    public static final CharSequence FONT_WOFF = HeaderString.valueOf("font/woff");

    /**
     * Web Open Font Format (WOFF):  {@code "font/woff2"}
     */
    public static final CharSequence FONT_WOFF2 = HeaderString.valueOf("font/woff2");

    /**
     * XHTML:  {@code "application/xhtml+xml"}
     */
    public static final CharSequence APPLICATION_XHTML_XML = HeaderString.valueOf("application/xhtml+xml");

    /**
     * Microsoft Excel:  {@code "application/vnd.ms-excel"}
     */
    public static final CharSequence APPLICATION_VND_MS_EXCEL = HeaderString.valueOf("application/vnd.ms-excel");

    /**
     * XML:  {@code "application/xml"}
     */
    public static final CharSequence APPLICATION_XML = HeaderString.valueOf("application/xml");

    /**
     * XUL:  {@code "application/vnd.mozilla.xul+xml"}
     */
    public static final CharSequence APPLICATION_VND_MOZILLA_XUL_XML = HeaderString.valueOf("application/vnd.mozilla.xul+xml");

    /**
     * ZIP archive:  {@code "application/zip"}
     */
    public static final CharSequence APPLICATION_ZIP = HeaderString.valueOf("application/zip");

    /**
     * 3GPP audio/video container:  {@code "video/3gpp"}
     */
    public static final CharSequence VIDEO_3GPP = HeaderString.valueOf("video/3gpp");

    /**
     * 3GPP2 audio/video container:  {@code "video/3gpp2"}
     */
    public static final CharSequence VIDEO_3GPP2 = HeaderString.valueOf("video/3gpp2");

    /**
     * 7-zip archive:  {@code "application/x-7z-compressed"}
     */
    public static final CharSequence APPLICATION_X_7Z_COMPRESSED = HeaderString.valueOf("application/x-7z-compressed");

    /**
     * Server-Sent-Events, a.k.a. SSE, a.k.a. Event Streams: {@code "text/event-stream"}
     */
    public static final CharSequence TEXT_EVENT_STREAM = HeaderString.valueOf("text/event-stream");

    /**
     * MKV video files {@code "video/x-matroska"}
     */
    public static final CharSequence VIDEO_X_MATROSKA = HeaderString.valueOf("video/x-matroska");

    /**
     * Web App Manifest files {@code "application/manifest+json"}
     */
    public static final CharSequence WEB_APP_MANIFEST = HeaderString.valueOf("application/manifest+json");

    private ContentTypes() {}
}
