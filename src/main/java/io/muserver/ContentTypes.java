package io.muserver;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AsciiString;

public class ContentTypes {

    /**
     * Plain text: {@code "text/plain"}
     */
    public static final CharSequence TEXT_PLAIN = HttpHeaderValues.TEXT_PLAIN;

    /**
     * Markdown: {@code "text/markdown"}
     */
    public static final CharSequence TEXT_MARKDOWN = AsciiString.cached("text/markdown");

    /**
     * AAC audio file:  {@code "audio/aac"}
     */
    public static final CharSequence AUDIO_AAC = AsciiString.cached("audio/aac");

    /**
     * AbiWord document:  {@code "application/x-abiword"}
     */
    public static final CharSequence APPLICATION_X_ABIWORD = AsciiString.cached("application/x-abiword");

    /**
     * AVI: Audio Video Interleave:  {@code "video/x-msvideo"}
     */
    public static final CharSequence VIDEO_X_MSVIDEO = AsciiString.cached("video/x-msvideo");

    /**
     * Amazon Kindle eBook format:  {@code "application/vnd.amazon.ebook"}
     */
    public static final CharSequence APPLICATION_VND_AMAZON_EBOOK = AsciiString.cached("application/vnd.amazon.ebook");

    /**
     * Any kind of binary data:  {@code "application/octet-stream"}
     */
    public static final CharSequence APPLICATION_OCTET_STREAM = AsciiString.cached("application/octet-stream");

    /**
     * BZip archive:  {@code "application/x-bzip"}
     */
    public static final CharSequence APPLICATION_X_BZIP = AsciiString.cached("application/x-bzip");

    /**
     * BZip2 archive:  {@code "application/x-bzip2"}
     */
    public static final CharSequence APPLICATION_X_BZIP2 = AsciiString.cached("application/x-bzip2");

    /**
     * C-Shell script:  {@code "application/x-csh"}
     */
    public static final CharSequence APPLICATION_X_CSH = AsciiString.cached("application/x-csh");

    /**
     * Cascading Style Sheets (CSS):  {@code "text/css"}
     */
    public static final CharSequence TEXT_CSS = AsciiString.cached("text/css");

    /**
     * Comma-separated values (CSV):  {@code "text/csv"}
     */
    public static final CharSequence TEXT_CSV = AsciiString.cached("text/csv");

    /**
     * Microsoft Word:  {@code "application/msword"}
     */
    public static final CharSequence APPLICATION_MSWORD = AsciiString.cached("application/msword");

    /**
     * MS Embedded OpenType fonts:  {@code "application/vnd.ms-fontobject"}
     */
    public static final CharSequence APPLICATION_VND_MS_FONTOBJECT = AsciiString.cached("application/vnd.ms-fontobject");

    /**
     * Electronic publication (EPUB):  {@code "application/epub+zip"}
     */
    public static final CharSequence APPLICATION_EPUB_ZIP = AsciiString.cached("application/epub+zip");

    /**
     * Graphics Interchange Format (GIF):  {@code "image/gif"}
     */
    public static final CharSequence IMAGE_GIF = AsciiString.cached("image/gif");

    /**
     * HyperText Markup Language (HTML):  {@code "text/html"}
     */
    public static final CharSequence TEXT_HTML = AsciiString.cached("text/html");

    /**
     * Icon format:  {@code "image/x-icon"}
     */
    public static final CharSequence IMAGE_X_ICON = AsciiString.cached("image/x-icon");

    /**
     * iCalendar format:  {@code "text/calendar"}
     */
    public static final CharSequence TEXT_CALENDAR = AsciiString.cached("text/calendar");

    /**
     * Java Archive (JAR):  {@code "application/java-archive"}
     */
    public static final CharSequence APPLICATION_JAVA_ARCHIVE = AsciiString.cached("application/java-archive");

    /**
     * JPEG images:  {@code "image/jpeg"}
     */
    public static final CharSequence IMAGE_JPEG = AsciiString.cached("image/jpeg");

    /**
     * JavaScript (ECMAScript):  {@code "application/javascript"}
     */
    public static final CharSequence APPLICATION_JAVASCRIPT = AsciiString.cached("application/javascript");

    /**
     * JSON format:  {@code "application/json"}
     */
    public static final CharSequence APPLICATION_JSON = AsciiString.cached("application/json");

    /**
     * Musical Instrument Digital Interface (MIDI):  {@code "audio/midi"}
     */
    public static final CharSequence AUDIO_MIDI = AsciiString.cached("audio/midi");

    /**
     * MPEG Video:  {@code "video/mpeg"}
     */
    public static final CharSequence VIDEO_MPEG = AsciiString.cached("video/mpeg");

    /**
     * Apple Installer Package:  {@code "application/vnd.apple.installer+xml"}
     */
    public static final CharSequence APPLICATION_VND_APPLE_INSTALLER_XML = AsciiString.cached("application/vnd.apple.installer+xml");

    /**
     * OpenDocument presentation document:  {@code "application/vnd.oasis.opendocument.presentation"}
     */
    public static final CharSequence APPLICATION_VND_OASIS_OPENDOCUMENT_PRESENTATION = AsciiString.cached("application/vnd.oasis.opendocument.presentation");

    /**
     * OpenDocument spreadsheet document:  {@code "application/vnd.oasis.opendocument.spreadsheet"}
     */
    public static final CharSequence APPLICATION_VND_OASIS_OPENDOCUMENT_SPREADSHEET = AsciiString.cached("application/vnd.oasis.opendocument.spreadsheet");

    /**
     * OpenDocument text document:  {@code "application/vnd.oasis.opendocument.text"}
     */
    public static final CharSequence APPLICATION_VND_OASIS_OPENDOCUMENT_TEXT = AsciiString.cached("application/vnd.oasis.opendocument.text");

    /**
     * OGG audio:  {@code "audio/ogg"}
     */
    public static final CharSequence AUDIO_OGG = AsciiString.cached("audio/ogg");

    /**
     * OGG video:  {@code "video/ogg"}
     */
    public static final CharSequence VIDEO_OGG = AsciiString.cached("video/ogg");

    /**
     * OGG:  {@code "application/ogg"}
     */
    public static final CharSequence APPLICATION_OGG = AsciiString.cached("application/ogg");

    /**
     * OpenType font:  {@code "font/otf"}
     */
    public static final CharSequence FONT_OTF = AsciiString.cached("font/otf");

    /**
     * Portable Network Graphics:  {@code "image/png"}
     */
    public static final CharSequence IMAGE_PNG = AsciiString.cached("image/png");

    /**
     * Adobe Portable Document Format (PDF):  {@code "application/pdf"}
     */
    public static final CharSequence APPLICATION_PDF = AsciiString.cached("application/pdf");

    /**
     * Microsoft PowerPoint:  {@code "application/vnd.ms-powerpoint"}
     */
    public static final CharSequence APPLICATION_VND_MS_POWERPOINT = AsciiString.cached("application/vnd.ms-powerpoint");

    /**
     * RAR archive:  {@code "application/x-rar-compressed"}
     */
    public static final CharSequence APPLICATION_X_RAR_COMPRESSED = AsciiString.cached("application/x-rar-compressed");

    /**
     * Rich Text Format (RTF):  {@code "application/rtf"}
     */
    public static final CharSequence APPLICATION_RTF = AsciiString.cached("application/rtf");

    /**
     * Bourne shell script:  {@code "application/x-sh"}
     */
    public static final CharSequence APPLICATION_X_SH = AsciiString.cached("application/x-sh");

    /**
     * Scalable Vector Graphics (SVG):  {@code "image/svg+xml"}
     */
    public static final CharSequence IMAGE_SVG_XML = AsciiString.cached("image/svg+xml");

    /**
     * Small web format (SWF) or Adobe Flash document:  {@code "application/x-shockwave-flash"}
     */
    public static final CharSequence APPLICATION_X_SHOCKWAVE_FLASH = AsciiString.cached("application/x-shockwave-flash");

    /**
     * Tape Archive (TAR):  {@code "application/x-tar"}
     */
    public static final CharSequence APPLICATION_X_TAR = AsciiString.cached("application/x-tar");

    /**
     * Tagged Image File Format (TIFF):  {@code "image/tiff"}
     */
    public static final CharSequence IMAGE_TIFF = AsciiString.cached("image/tiff");

    /**
     * Typescript file:  {@code "application/typescript"}
     */
    public static final CharSequence APPLICATION_TYPESCRIPT = AsciiString.cached("application/typescript");

    /**
     * TrueType Font:  {@code "font/ttf"}
     */
    public static final CharSequence FONT_TTF = AsciiString.cached("font/ttf");

    /**
     * Microsoft Visio:  {@code "application/vnd.visio"}
     */
    public static final CharSequence APPLICATION_VND_VISIO = AsciiString.cached("application/vnd.visio");

    /**
     * Waveform Audio Format:  {@code "audio/x-wav"}
     */
    public static final CharSequence AUDIO_X_WAV = AsciiString.cached("audio/x-wav");

    /**
     * WEBM audio:  {@code "audio/webm"}
     */
    public static final CharSequence AUDIO_WEBM = AsciiString.cached("audio/webm");

    /**
     * WEBM video:  {@code "video/webm"}
     */
    public static final CharSequence VIDEO_WEBM = AsciiString.cached("video/webm");

    /**
     * WEBP image:  {@code "image/webp"}
     */
    public static final CharSequence IMAGE_WEBP = AsciiString.cached("image/webp");

    /**
     * Web Open Font Format (WOFF):  {@code "font/woff"}
     */
    public static final CharSequence FONT_WOFF = AsciiString.cached("font/woff");

    /**
     * Web Open Font Format (WOFF):  {@code "font/woff2"}
     */
    public static final CharSequence FONT_WOFF2 = AsciiString.cached("font/woff2");

    /**
     * XHTML:  {@code "application/xhtml+xml"}
     */
    public static final CharSequence APPLICATION_XHTML_XML = AsciiString.cached("application/xhtml+xml");

    /**
     * Microsoft Excel:  {@code "application/vnd.ms-excel"}
     */
    public static final CharSequence APPLICATION_VND_MS_EXCEL = AsciiString.cached("application/vnd.ms-excel");

    /**
     * XML:  {@code "application/xml"}
     */
    public static final CharSequence APPLICATION_XML = AsciiString.cached("application/xml");

    /**
     * XUL:  {@code "application/vnd.mozilla.xul+xml"}
     */
    public static final CharSequence APPLICATION_VND_MOZILLA_XUL_XML = AsciiString.cached("application/vnd.mozilla.xul+xml");

    /**
     * ZIP archive:  {@code "application/zip"}
     */
    public static final CharSequence APPLICATION_ZIP = AsciiString.cached("application/zip");

    /**
     * 3GPP audio/video container:  {@code "video/3gpp"}
     */
    public static final CharSequence VIDEO_3GPP = AsciiString.cached("video/3gpp");

    /**
     * 3GPP2 audio/video container:  {@code "video/3gpp2"}
     */
    public static final CharSequence VIDEO_3GPP2 = AsciiString.cached("video/3gpp2");

    /**
     * 7-zip archive:  {@code "application/x-7z-compressed"}
     */
    public static final CharSequence APPLICATION_X_7Z_COMPRESSED = AsciiString.cached("application/x-7z-compressed");

    /**
     * Server-Sent-Events, a.k.a. SSE, a.k.a. Event Streams: {@code "text/event-stream"}
     */
    public static final CharSequence TEXT_EVENT_STREAM = AsciiString.cached("text/event-stream");

    /**
     * MKV video files {@code "video/x-matroska"}
     */
    public static final CharSequence VIDEO_X_MATROSKA = AsciiString.cached("video/x-matroska");

    private ContentTypes() {}
}
