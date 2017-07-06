package it.unibo.studio.iot.scc.messages;

public class Message<T> {

	private T content;

	public Message(T c) {
		this.content = c;
	}

	public T getContent() {
		return content;
	}
}
