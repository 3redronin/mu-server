# HTTP/2 coverage tracker

Legend:

* `[x]` = substantial automated coverage exists and the feature looks intentionally implemented
* `[~]` = partially covered, core behavior exists, or implementation is present but not fully audited/documented yet
* `[ ]` = not yet covered, deferred, or intentionally unsupported

---

* 3. [x] Starting HTTP/2
 * 3.1. [x] HTTP/2 Version Identification
 * 3.2. [x] Starting HTTP/2 for "https" URIs
 * 3.3. [x] Starting HTTP/2 with Prior Knowledge
 * 3.4. [x] HTTP/2 Connection Preface
* 4. [x] HTTP Frames
 * 4.1. [x] Frame Format
 * 4.2. [x] Frame Size
 * 4.3. [x] Field Section Compression and Decompression
 * 4.3.1. [x] Compression State
* 5. [~] Streams and Multiplexing
 * 5.1. [x] Stream States
 * 5.1.1. [x] Stream Identifiers
 * 5.1.2. [x] Stream Concurrency
 * 5.2. [x] Flow Control
 * 5.2.1. [x] Flow-Control Principles
 * 5.2.2. [x] Appropriate Use of Flow Control
 * 5.2.3. [ ] Flow-Control Performance
 * 5.3. [~] Prioritization
 * 5.3.1. [x] Background on Priority in RFC 7540
 * 5.3.2. [~] Priority Signaling in This Document
 * 5.4. [x] Error Handling
 * 5.4.1. [x] Connection Error Handling
 * 5.4.2. [x] Stream Error Handling
 * 5.4.3. [x] Connection Termination
 * 5.5. [x] Extending HTTP/2
* 6. [x] Frame Definitions
 * 6.1. [x] DATA
 * 6.2. [x] HEADERS
 * 6.3. [x] PRIORITY
 * 6.4. [x] RST_STREAM
 * 6.5. [x] SETTINGS
 * 6.5.1. [x] SETTINGS Format
 * 6.5.2. [x] Defined Settings
 * 6.5.3. [x] Settings Synchronization
 * 6.6. [ ] PUSH_PROMISE (intentionally unsupported)
 * 6.7. [x] PING
 * 6.8. [x] GOAWAY
 * 6.9. [x] WINDOW_UPDATE
 * 6.9.1. [x] The Flow-Control Window
 * 6.9.2. [x] Initial Flow-Control Window Size
 * 6.9.3. [x] Reducing the Stream Window Size
 * 6.10. [x] CONTINUATION
* 7. [x] Error Codes
* 8. [~] Expressing HTTP Semantics in HTTP/2
 * 8.1. [x] HTTP Message Framing
 * 8.1.1. [x] Malformed Messages
 * 8.2. [x] HTTP Fields
 * 8.2.1. [x] Field Validity
 * 8.2.2. [x] Connection-Specific Header Fields
 * 8.2.3. [x] Compressing the Cookie Header Field
 * 8.3. [x] HTTP Control Data
 * 8.3.1. [x] Request Pseudo-Header Fields
 * 8.3.2. [x] Response Pseudo-Header Fields
 * 8.4. [ ] Server Push (intentionally unsupported)
 * 8.4.1. [ ] Push Requests (intentionally unsupported)
 * 8.4.2. [ ] Push Responses (intentionally unsupported)
 * 8.5. [ ] The CONNECT Method
 * 8.6. [ ] The Upgrade Header Field (h2c upgrade intentionally unsupported)
 * 8.7. [x] Request Reliability
 * 8.8. [~] Examples
 * 8.8.1. [~] Simple Request
 * 8.8.2. [~] Simple Response
 * 8.8.3. [~] Complex Request
 * 8.8.4. [~] Response with Body
 * 8.8.5. [x] Informational Responses
* 9. [~] HTTP/2 Connections
 * 9.1. [~] Connection Management
 * 9.1.1. [ ] Connection Reuse
 * 9.2. [ ] Use of TLS Features
 * 9.2.1. [ ] TLS 1.2 Features
 * 9.2.2. [ ] TLS 1.2 Cipher Suites
 * 9.2.3. [ ] TLS 1.3 Features
* 10. [ ] Security Considerations
 * 10.1. [ ] Server Authority
 * 10.2. [ ] Cross-Protocol Attacks
 * 10.3. [ ] Intermediary Encapsulation Attacks
 * 10.4. [ ] Cacheability of Pushed Responses
 * 10.5. [~] Denial-of-Service Considerations
 * 10.5.1. [~] Limits on Field Block Size
 * 10.5.2. [ ] CONNECT Issues
 * 10.6. [~] Use of Compression
 * 10.7. [~] Use of Padding
 * 10.8. [ ] Privacy Considerations
 * 10.9. [ ] Remote Timing Attacks
* 11. [ ] IANA Considerations
 * 11.1. [ ] HTTP2-Settings Header Field Registration
 * 11.2. [ ] The h2c Upgrade Token (h2c intentionally unsupported)
* 12. [ ] References
 * 12.1. [ ] Normative References
 * 12.2. [ ] Informative References
* A. [ ] Prohibited TLS 1.2 Cipher Suites
* B. [ ] Changes from RFC 7540
