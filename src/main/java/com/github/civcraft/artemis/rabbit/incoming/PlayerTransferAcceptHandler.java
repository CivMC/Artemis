package com.github.civcraft.artemis.rabbit.incoming;

import org.json.JSONObject;

import com.github.civcraft.zeus.rabbit.incoming.InteractiveRabbitCommand;
import com.github.civcraft.zeus.rabbit.sessions.PlayerTransferSession;
import com.github.civcraft.zeus.servers.ConnectedServer;

public class PlayerTransferAcceptHandler extends InteractiveRabbitCommand<PlayerTransferSession> {

	@Override
	public boolean handleRequest(PlayerTransferSession connState, ConnectedServer sendingServer, JSONObject data) {
		// TODO Is there anything we want to do with this?
		return false;
	}

	@Override
	public String getIdentifier() {
		return "accept_transfer";
	}

	@Override
	public boolean createSession() {
		return false;
	}
	
}
