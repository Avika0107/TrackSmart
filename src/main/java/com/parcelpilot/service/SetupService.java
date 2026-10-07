package com.parcelpilot.service;

import com.parcelpilot.config.AppProperties;
import com.parcelpilot.model.AppState;
import com.parcelpilot.model.User;
import com.parcelpilot.repository.AppStateRepository;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SetupService {

    private final AppProperties props;
    private final AppStateRepository state;
    private final Environment env;

    public SetupService(AppProperties props, AppStateRepository state, Environment env) {
        this.props = props;
        this.state = state;
        this.env = env;
    }

    public boolean isDemo() {
        for (String p : env.getActiveProfiles()) {
            if (p.equals("demo")) return true;
        }
        return env.getActiveProfiles().length == 0; // default profile is demo
    }

    public String forwardToAddress() {
        String explicit = props.getMail().getForwardTo();
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (props.getMail().getUser() != null && !props.getMail().getUser().isBlank()) {
            return props.getMail().getUser();
        }
        return "parcel.pilot.demo.inbox@gmail.com";
    }

    public String forwardingAddressFor(User user) {
        String base = forwardToAddress();
        int at = base.indexOf('@');
        return at < 0 ? base + "+" + user.getInboundAlias() : base.substring(0, at) + "+" + user.getInboundAlias() + base.substring(at);
    }

    public String forwardingConfirmationCode() {
        return state.findById(AppState.FORWARDING_CODE_KEY).map(AppState::getValue).orElse(null);
    }

    public void saveForwardingCode(String code) {
        state.save(new AppState(AppState.FORWARDING_CODE_KEY, code));
    }

    /** Downloadable Gmail filter XML pre-filled with allowlist senders and the forward-to address. */
    public String gmailFilterXml(User user) {
        String forwardTo = forwardingAddressFor(user);
        String from = String.join("|", props.getAllowedSenders());
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:apps="http://schemas.google.com/apps/2006">
                  <title>ParcelPilot filter</title>
                  <entry>
                    <category term="filter"/>
                    <title>ParcelPilot - forward retailer shipping mails</title>
                    <content>Auto-forwards order and shipping emails to ParcelPilot.</content>
                    <apps:property name="from" value="%s"/>
                    <apps:property name="forwardTo" value="%s"/>
                    <apps:property name="sizeOperator" value="s_sl"/>
                    <apps:property name="sizeUnit" value="s_smb"/>
                  </entry>
                </feed>
                """.formatted(from, forwardTo);
    }

    public List<String> allowlist() {
        return props.getAllowedSenders();
    }
}
