package ronin.muserver;

public interface MuResponse {

	int status();
	void status(int value);

	void write(String text);
}
