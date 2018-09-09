package io.muserver;

import java.io.File;
import java.util.concurrent.ExecutorService;

class ServerSettings {

    CompressionSettings compressionSettings = new CompressionSettings();
    ExecutorService executorService;
    RequestParser.Options parserOptions;

    File tempUploadDir = null;

}
