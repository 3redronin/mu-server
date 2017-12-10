package ronin.muserver;

import java.util.concurrent.Future;

public interface MuResponse {

	int status();
	void status(int value);

	Future<Void> write(String text);
}
