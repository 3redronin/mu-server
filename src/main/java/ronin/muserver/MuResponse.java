package ronin.muserver;

import java.util.concurrent.Future;

public interface MuResponse {

	int status();
	void status(int value);

	Future<Void> writeAsync(String text);
	void write(String text);
}
