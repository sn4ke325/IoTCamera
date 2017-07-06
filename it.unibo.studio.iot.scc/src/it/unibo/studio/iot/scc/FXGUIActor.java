package it.unibo.studio.iot.scc;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import javafx.fxml.FXML;
import javafx.scene.control.Button;

public class FXGUIActor extends AbstractActor {

	@FXML
	private Button camera_button;
	@FXML
	private Button video_button;

	private ActorRef vaSupervisor;

	public FXGUIActor(ActorRef ref) {
		this.vaSupervisor = ref;
	}

	public static Props props(ActorRef ref) {
		return Props.create(FXGUIActor.class, ref);
	}

	@Override
	public Receive createReceive() {
		// TODO Auto-generated method stub
		return null;
	}

}
