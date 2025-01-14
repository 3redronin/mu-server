package io.muserver.handlers;

import io.muserver.Mutils;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Stream;

import static io.muserver.Mutils.urlEncode;
import static java.util.Collections.singletonMap;

class DirectoryLister {
    private final Writer writer;
    private final String contextPath;
    private final String relativePath;
    private final ResourceProvider provider;
    private final String directoryListingCss;
    private final DateTimeFormatter dateFormatter;

    DirectoryLister(Writer writer, ResourceProvider provider, String contextPath, String relativePath, String directoryListingCss, DateTimeFormatter dateFormatter) {
        this.writer = writer;
        this.provider = provider;
        this.contextPath = contextPath;
        this.relativePath = relativePath;
        this.directoryListingCss = directoryListingCss;
        this.dateFormatter = dateFormatter;
    }

    void render() throws IOException {
        El html = new El("html").open();
        El head = new El("head").open();
        String title = "Index of " + Mutils.urlDecode(contextPath + relativePath);
        render("title", title);
        new El("style").open().contentRaw(directoryListingCss).close();
        head.close();
        El body = new El("body").open();

        render("h1", title);
        El main = new El("main").open();


        El table = new El("table").open(singletonMap("class", "dirListing operation"));
        El thead = new El("thead").open();

        El theadRow = new El("tr").open();
        render("th", "Filename");
        new El("th").open(singletonMap("class", "size")).content("Size").close();
        render("th", "Last modified");
        theadRow.close();
        thead.close();

        El tbody = new El("tbody").open();

        if (relativePath.length() > 1) {
            El parentDirRow = new El("tr").open();
            El parentLinkTd = new El("td").open(singletonMap("class", "dir"));
            new El("a").open(singletonMap("href", "..")).content("Parent directory").close();
            parentLinkTd.close();
            render("td", "");
            render("td", "");
            parentDirRow.close();
        }

        try (Stream<Path> files = provider.listFiles()) {
            files.forEach(path -> {
                    try {
                        boolean isDir = Files.isDirectory(path);
                        El tr = new El("tr").open(singletonMap("class", isDir ? "dir" : "file"));
                        String filename = path.getFileName().toString();
                        El nameTd = new El("td").open();
                        new El("a").open(singletonMap("href", urlEncode(filename) + (isDir ? "/" : "")))
                            .content(isDir ? filename + "/" : filename).close();
                        nameTd.close();
                        if (isDir) {
                            render("td", "");
                            render("td", "");
                        } else {
                            new El("td").open(singletonMap("class", "size")).content(String.valueOf(Files.size(path))).close();
                            El timeTd = new El("td").open();
                            Instant lastMod = Files.getLastModifiedTime(path).toInstant();
                            new El("time").open(singletonMap("datetime", lastMod.toString()))
                                .content(dateFormatter.format(lastMod)).close();
                            timeTd.close();
                        }
                        tr.close();
                    } catch (IOException e) {
                        throw new RuntimeException("Error rendering file", e);
                    }
                });
        }

        tbody.close();
        table.close();

        main.close();
        body.close();

        new El("script").open()
            //language=JavaScript 1.6
            .contentRaw(
                "document.addEventListener('DOMContentLoaded', () => {\n" +
                    "    " +
                    "const getCellValue = (tr, idx) => {\n" +
                    "        let td = tr.children[idx];\n" +
                    "        let time = td.querySelector('time');\n" +
                    "        return time ? time.dateTime : td.textContent;\n" +
                    "    };\n" +
                    "\n" +
                    "    " +
                    "const comparer = (idx, asc) => (a, b) => ((v1, v2) =>\n" +
                    "        " +
                    "    v1 !== '' && v2 !== '' && !isNaN(v1) && !isNaN(v2) ? v1 - v2 : v1.toString().localeCompare(v2)\n" +
                    "    )(getCellValue(asc ? a : b, idx), getCellValue(asc ? b : a, idx));\n" +
                    "\n" +
                    "    let asc = true;\n" +
                    "    " +
                    "document.querySelectorAll('th').forEach(th => {\n" +
                    "            th.style.cursor = 'pointer';\n" +
                    "            th.title = 'Sort by ' + th.textContent;\n" +
                    "            th.addEventListener('click', (() => {\n" +
                    "                const tbody = th.closest('table').querySelector('tbody');\n" +
                    "                " +
                    "Array.from(tbody.querySelectorAll('tr'))\n" +
                    "                    .sort(comparer(Array.from(th.parentNode.children).indexOf(th), asc = !asc))\n" +
                    "                    .forEach(tr =>" +
                    " tbody.appendChild(tr)" +
                    ");\n" +
                    "            " +
                    "}))\n" +
                    "        }\n" +
                    "    );\n" +
                "});").close(); // adapted from https://stackoverflow.com/a/49041392/131578

        html.close();
    }

    private void render(String tag, String value) throws IOException {
        new El(tag).open().content(value).close();
    }


    class El {
        private final String tag;

        private El(String tag) {
            this.tag = tag;
        }

        El open() throws IOException {
            return open(null);
        }

        El open(@Nullable Map<String, String> attributes) throws IOException {
            writer.write("<" + tag);
            if (attributes != null) {
                for (Map.Entry<String, String> entry : attributes.entrySet()) {
                    writer.write(" " + Mutils.htmlEncode(entry.getKey()) + "=\"" + Mutils.htmlEncode(entry.getValue()) + "\"");
                }
            }
            writer.write('>');
            return this;
        }

        El contentRaw(String val) throws IOException {
            writer.write(val);
            return this;
        }

        El content(@Nullable Object@Nullable... vals) throws IOException {
            if (vals != null) {
                for (Object val : vals) {
                    if (val != null && !(val instanceof El)) {
                        String stringVal = val.toString();
                        writer.write(Mutils.htmlEncode(stringVal).replace("\n", "<br>"));
                    }
                }
            }
            return this;
        }

        public El close() throws IOException {
            writer.write("</" + tag + ">");
            return this;
        }

    }
}
