package io.muserver;

public interface MuHandler {

	boolean handle(MuRequest request, MuResponse response) throws Exception;

}
