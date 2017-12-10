package ronin.muserver;

public interface SyncHandler {

	boolean handle(MuRequest request, MuResponse response);

}
