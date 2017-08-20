package it.unibo.studio.iot.scc.messages;

public final class StartVideoCapture {

	private boolean video_debug;

	public StartVideoCapture() {
		this.video_debug = false;
	}

	public StartVideoCapture(boolean hasVideo) {
		this.video_debug = hasVideo;
	}

	public boolean withVideo() {
		return this.video_debug;
	}

}