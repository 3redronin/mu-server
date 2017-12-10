package ronin.muserver;

import java.net.URI;

public interface MuRequest {

	HttpMethod method();
	URI uri();
	URI serverURI();


}
