package it.unibo.studio.iot.scc;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import it.unibo.studio.iot.scc.messages.Message;
import it.unibo.studio.iot.scc.messages.StartGUI;

//Camera supervisor that will manage all camera functions such as video capturing, analysis and remote communication...

public class IotCameraSupervisor extends AbstractActor {

	private final String groupId;

	private final String deviceId;

	private ActorRef uiSupervisor;
	private ActorRef videoAnalysisSupervisor;
	private ActorRef counterSupervisor;
	private ActorRef remoteCommsSupervisor;
	private ActorRef GUI;

	private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

	public IotCameraSupervisor(String groupId, String deviceId) {
		this.groupId = groupId;
		this.deviceId = deviceId;
	}

	public void preStart() {

		this.uiSupervisor = this.getContext().actorOf(IotCameraUISupervisor.props(), "ui-supervisor");
		this.videoAnalysisSupervisor = this.getContext().actorOf(IotCameraVASupervisor.props(0),
				"video-analysis-supervisor");
	}

	public static Props props(String groupId, String deviceId) {
		return Props.create(IotCameraSupervisor.class, groupId, deviceId);
	}


	@Override
	public Receive createReceive() {
		// TODO Auto-generated method stub
		return receiveBuilder().match(StartGUI.class, r -> {
			//this.startGUI();
			getSender().tell(new Message<String>("GUI started"), this.self());
		}).build();
	}
}
