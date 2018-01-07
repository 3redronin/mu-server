package ronin.muserver;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class GrowableByteBufferInputStreamTest {

	private final ExecutorService executor = Executors.newFixedThreadPool(50);
	private final Random rng = new Random();

	@Test
	public void itCanHaveStuffAddedAsyncyAndClosedButReadInASyncManner() throws IOException {
		GrowableByteBufferInputStream stream = new GrowableByteBufferInputStream();

		int totalSize = 0;
		List<ByteBuffer> generated = new ArrayList<>();
		for (int i = 0; i < 1; i++) {
			ByteBuffer buffer = randomBuffer();
			totalSize += buffer.remaining();
			generated.add(buffer);
		}

		ByteBuffer expected = ByteBuffer.allocate(totalSize);
		for (ByteBuffer byteBuffer : generated) {
			expected.put(byteBuffer);
			byteBuffer.position(0);
		}
		expected.position(0);
		executor.submit(() -> {
			for (ByteBuffer byteBuffer : generated) {
				stream.handOff(byteBuffer);
			}
			try {
				stream.close();
			} catch (IOException e) {
			}
		});

		ByteBuffer actual = ByteBuffer.allocate(expected.capacity());
		byte[] data = new byte[128];
		int read;
		while ((read = stream.read(data)) > -1) {
            assertThat(read, greaterThan(0));
			actual.put(data, 0, read);
		}

		actual.position(0);
		assertThat(actual.asCharBuffer(), equalTo(expected.asCharBuffer()));
	}

	private ByteBuffer randomBuffer() {
		int size = 1 + rng.nextInt(16384);
		byte[] bytes = new byte[size];
		rng.nextBytes(bytes);
		return ByteBuffer.wrap(bytes);
	}
}