package it.unibo.studio.iot.scc.messages;

public class Count {
	private int amount;

	public Count(int a) {
		this.amount = a;
	}

	public int getContent() {
		return amount;
	}
}
