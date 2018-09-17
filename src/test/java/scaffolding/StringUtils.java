package scaffolding;

import java.util.Random;

public class StringUtils {
    public static String randomStringOfLength(int numberOfCharacters) {
        Random rng = new Random();
        StringBuilder sb = new StringBuilder(numberOfCharacters);
        for (int i = 0; i < numberOfCharacters; i++) {
            char c = (char) (rng.nextInt(30000) + 33);
            sb.append(c);
        }
        return sb.toString();
    }
    public static String randomAsciiStringOfLength(int numberOfCharacters) {
        Random rng = new Random();
        StringBuilder sb = new StringBuilder(numberOfCharacters);
        for (int i = 0; i < numberOfCharacters; i++) {
            char c = (char) (rng.nextInt(89) + 33);
            sb.append(c);
        }
        return sb.toString();
    }
	public static byte[] randomBytes(int len) {
	    byte[] res = new byte[len];
        Random rng = new Random();
        rng.nextBytes(res);
        return res;
    }
}
