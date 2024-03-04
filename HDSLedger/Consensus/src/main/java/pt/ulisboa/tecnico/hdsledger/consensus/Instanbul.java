package pt.ulisboa.tecnico.hdsledger.consensus;

import pt.ulisboa.tecnico.hdsledger.consensus.message.*;
import pt.ulisboa.tecnico.hdsledger.consensus.message.builder.ConsensusMessageBuilder;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;

import java.text.MessageFormat;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.stream.IntStream;
import java.util.Collection;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.Collections;
import java.util.function.Consumer;

/**
 * Instance of Instanbul consensus protocol
 * It's assumed that a single thread executes an instance at each moment. 
 */
public class Instanbul {
	private static final CustomLogger LOGGER = new CustomLogger(Instanbul.class.getName());

	// Process configuration (includes its id)
	private final ProcessConfig config;

	// The identifier of the consensus instance
	private final int lambda;

	// The current round
	private int ri;

	// The round at which the process has prepared
	private Optional<Integer> pri;

	// The value for which the process has prepared
	private Optional<String> pvi;

	// The value passed as input to this instance
	private Optional<String> inputValuei;

	// Consensus instance -> Round -> List of prepare messages
	private final MessageBucket prepareMessages;

	// Consensus instance -> Round -> List of commit messages
	private final MessageBucket commitMessages;

	// Store if already received pre-prepare for a given round
	private final Map<Integer, Boolean> receivedPrePrepare = new HashMap<>();

	// Callbacks to be called on deliver
	private final List<Consumer<String>> observers = new ArrayList();

	// Wheter start was called already
	private boolean started = false;

	// FIXME (dsa): I'm not sure that this is needed
	private Optional<CommitMessage> commitMessage;

	// Decided value
	private Optional<String> decision;

	// PRE-PREPARE messages from future rounds (round -> list of messages)
	private Map<Integer, List<ConsensusMessage>> stashedPrePrepare = new HashMap<>();

	// PREPARE message from future rounds
	private Map<Integer, List<ConsensusMessage>> stashedPrepare = new HashMap<>();
	

	public Instanbul(ProcessConfig config, int lambda) {
		this.lambda = lambda;
		this.config = config;

		this.ri = 0;
		this.pri = Optional.empty();
		this.pvi = Optional.empty();
		this.inputValuei = Optional.empty();
		this.decision = Optional.empty();

		this.commitMessage = Optional.empty();
		// this.instanceInfo = Optional.empty();
		this.prepareMessages = new MessageBucket(config.getN());
		this.commitMessages = new MessageBucket(config.getN());
	}

	/**
	 * Add callback to be called on deliver
	 *
	 * @param observer A function that takes a single argument and returns void
	 * to be called when the instance delivers
	 */
	public void registerObserver(Consumer<String> observer) {
		observers.add(observer);	
	}

	/**
	 * Utility to create PrePrepareMessages
	 */
	private ConsensusMessage createPrePrepareMessage(String value, int instance, int round, int receiver) {
		PrePrepareMessage prePrepareMessage = new PrePrepareMessage(value);

		ConsensusMessage consensusMessage = new ConsensusMessageBuilder(config.getId(), Message.Type.PRE_PREPARE)
			.setConsensusInstance(instance)
			.setRound(round)
			.setMessage(prePrepareMessage.toJson())
			.setReceiver(receiver)
			.build();

		return consensusMessage;
	}

	/**
	 * Utility to create PrepareMessages
	 */
	private ConsensusMessage createPrepareMessage(PrePrepareMessage prePrepareMessage, String value, int instance, int round, int receiver, int senderId, int senderMessageId) {
		PrepareMessage prepareMessage = new PrepareMessage(prePrepareMessage.getValue());

		ConsensusMessage consensusMessage = new ConsensusMessageBuilder(config.getId(), Message.Type.PREPARE)
			.setConsensusInstance(instance)
			.setRound(round)
			.setMessage(prepareMessage.toJson())
			.setReplyTo(senderId)
			.setReplyToMessageId(senderMessageId)
			.setReceiver(receiver)
			.build();

		return consensusMessage;
	}

	/**
	 * Utility to create CommitMessages
	 */
	private ConsensusMessage createCommitMessage(int instance, int round, int receiver, String commitMessageJson) {
		return new ConsensusMessageBuilder(config.getId(), Message.Type.COMMIT)
			.setConsensusInstance(instance)
			.setRound(round)
			.setMessage(commitMessageJson)
			.setReceiver(receiver)
			.build();
	}


	/*
	 * Start an instance of consensus for a value
	 * Only the current leader will start a consensus instance,
	 * the remaining nodes only update values.
	 *
	 * @param inputValue Value to value agreed upon
	 */
	public List<ConsensusMessage> start(String inputValue) {
		if (started) {
			LOGGER.log(Level.INFO, MessageFormat.format("{0} - Node already started consensus for instance {1}",
						config.getId(), this.lambda));
			return new ArrayList<>();
		} else {
			started = true;
		}

		if (!this.inputValuei.isPresent()) {
			this.inputValuei = Optional.of(inputValue);
		}

		// Leader broadcasts PRE-PREPARE message
		if (isLeader(this.lambda, this.ri, this.config.getId())) {
			LOGGER.log(Level.INFO,
					MessageFormat.format("{0} - Node is leader, sending PRE-PREPARE message", config.getId()));

			// Broadcast message PRE-PREPARE(lambda, ri, inputvalue)
			return IntStream.range(0, this.config.getN())
				.mapToObj(receiver -> this.createPrePrepareMessage(inputValue, this.lambda, this.ri, receiver))
				.collect(Collectors.toList());
		} else {
			LOGGER.log(Level.INFO,
					MessageFormat.format("{0} - Node is not leader, waiting for PRE-PREPARE message", config.getId()));

			return new ArrayList();
		}
	}

	/*
	 * Handle pre prepare messages and if the message
	 * came from leader and is justified them broadcast prepare
	 *
	 * @param message Message to be handled
	 */
	private List<ConsensusMessage> prePrepare(ConsensusMessage message) {

		// TODO (dsa): horrible, refactor message structure
		
		int round = message.getRound();
		int senderId = message.getSenderId();

		if (round != this.ri) {
			LOGGER.log(Level.WARNING,
					MessageFormat.format(
						"{0} - Shouldn't process PREPARE message from {1}: Consensus Instance {2}, Round {3} at round {4}",
						config.getId(), senderId, this.lambda, round, this.ri));
			throw new RuntimeException("Should not be processing PREPARE message in wrong round");
		}

		int senderMessageId = message.getMessageId();

		PrePrepareMessage prePrepareMessage = message.deserializePrePrepareMessage();

		String value = prePrepareMessage.getValue();

		LOGGER.log(Level.INFO,
			MessageFormat.format(
				"{0} - Received PRE-PREPARE message from {1} Consensus Instance {2}, Round {3}",
				config.getId(), senderId, this.lambda, round));
		
		// Verify if pre-prepare was sent by leader
		if (!isLeader(this.lambda, round, senderId)) {
		    return new ArrayList<>();
		}

		// Set instance value
		// if (!this.instanceInfo.isPresent()) {
		// 	this.instanceInfo = Optional.of(new InstanceInfo(value));	
		// }
		if (!this.inputValuei.isPresent()) {
			this.inputValuei = Optional.of(value);
		}

		// Resend in case message hasn't yet reached leader
		// TODO (dsa): why not return empty list
		if (receivedPrePrepare.put(round, true) != null) {
			LOGGER.log(Level.INFO,
					MessageFormat.format(
						"{0} - Already received PRE-PREPARE message for Consensus Instance {1}, Round {2}, "
						+ "replying again to make sure it reaches the initial sender",
						config.getId(), this.lambda, round));
		}

		// Broadcast Prepare (labmda, round, value)
		return IntStream.range(0, this.config.getN())
			.mapToObj(receiver -> this.createPrepareMessage(prePrepareMessage, value, this.lambda, round, receiver, senderId, senderMessageId))
			.collect(Collectors.toList());
	}

	/*
	 * Handle prepare messages and if there is a valid quorum broadcast commit
	 *
	 * @param message Message to be handled
	 */
	private List<ConsensusMessage> prepare(ConsensusMessage message) {


		int round = message.getRound();
		int senderId = message.getSenderId();

		if (round != this.ri) {
			LOGGER.log(Level.WARNING,
					MessageFormat.format(
						"{0} - Shouldn't process PREPARE message from {1}: Consensus Instance {2}, Round {3} at round {4}",
						config.getId(), senderId, this.lambda, round, this.ri));
			throw new RuntimeException("Should not be processing PREPARE message in wrong round");
		}

		PrepareMessage prepareMessage = message.deserializePrepareMessage();

		String value = prepareMessage.getValue();

		LOGGER.log(Level.INFO,
				MessageFormat.format(
					"{0} - Received PREPARE message from {1}: Consensus Instance {2}, Round {3}",
					config.getId(), senderId, this.lambda, round));

		// Doesn't add duplicate messages
		prepareMessages.addMessage(message);

		// Set instance value
		if (!this.inputValuei.isPresent()) {
			this.inputValuei = Optional.of(value);
		}

		// If already prepared, won't prepare again
		if (this.pri.isPresent()) {
			LOGGER.log(Level.INFO,
					MessageFormat.format(
						"{0} - Already received PREPARE message for Consensus Instance {1}, Round {2}, "
						+ "ignoring PREPARE that was just received",
						config.getId(), this.lambda, round));

			return new ArrayList<>();
		}

		// Find value with valid quorum
		Optional<String> preparedValue = prepareMessages.hasValidPrepareQuorum(config.getId(), this.lambda, round);
		
		if (preparedValue.isPresent()) {

			LOGGER.log(Level.INFO,
					MessageFormat.format(
						"{0} - Found quorum of PREPARE messages for Consensus Instance {1}, Round {2}",
						config.getId(), this.lambda, round));

			// Prepare for this instance
			this.pri = Optional.of(round);
			this.pvi = Optional.of(preparedValue.get());

			Collection<ConsensusMessage> sendersMessage = prepareMessages.getMessages(this.lambda, round)
				.values();

			this.commitMessage = Optional.of(new CommitMessage(preparedValue.get()));

			return IntStream.range(0, this.config.getN())
				.mapToObj(receiver -> 
					createCommitMessage(this.lambda, round, receiver, this.commitMessage.get().toJson()))
				.collect(Collectors.toList());
		}

		return new ArrayList<>();
	}

	/*
	 * Handle commit messages and decide if there is a valid quorum
	 *
	 * @param message Message to be handled
	 */
	private List<ConsensusMessage> commit(ConsensusMessage message) {

		int round = message.getRound();

		LOGGER.log(Level.INFO,
				MessageFormat.format("{0} - Received COMMIT message from {1}: Consensus Instance {2}, Round {3}",
					config.getId(), message.getSenderId(), this.lambda, round));

		commitMessages.addMessage(message);

		Optional<String> commitValue = commitMessages.hasValidCommitQuorum(config.getId(),
				this.lambda, round);

		if (commitValue.isPresent()) {

			String value = commitValue.get();

			decide(value);

			LOGGER.log(Level.INFO,
					MessageFormat.format(
						"{0} - Decided on Consensus Instance {1}, Round {2}, Successful? {3}",
						config.getId(), this.lambda, round, true));

			return new ArrayList<>();
		}

		return new ArrayList<>();
	}

	public List<ConsensusMessage> handleMessage(ConsensusMessage message) {
		int mround = message.getRound();

		return switch (message.getType()) {
			case PRE_PREPARE -> {
				if (mround == this.ri) {
					yield prePrepare(message);
				} else if (message.getRound() < this.ri) {
					// PRE-PREPARES from previous rounds can be dropped
					yield new ArrayList<>();
				} else {
					// PRE-PREPARE from future rounds can't processed right
					// now, but should be processed when we change rounds
					LOGGER.log(Level.INFO,
							MessageFormat.format(
								"{0} - Stashed PRE-PREPARE at instance {1}, Round {2} (message was for {3})",
						config.getId(), this.lambda, this.ri, mround));
					this.stashedPrePrepare.putIfAbsent(mround, new ArrayList<>());
					List<ConsensusMessage> stashed = stashedPrePrepare.get(mround);
					stashed.add(message);
					yield new ArrayList<>();
				}
			}

			case PREPARE -> {
				if (mround == this.ri) {
					yield prepare(message);
				} else if (message.getRound() < this.ri) {
					// PREPARES from previous rounds can be dropped
					yield new ArrayList<>();
				} else {
					// PREPARE from future rounds can't processed right
					// now, but should be processed when round is changed
					LOGGER.log(Level.INFO,
							MessageFormat.format(
								"{0} - Stashed PRE-PREPARE at instance {1}, Round {2} (message was for {3})",
						config.getId(), this.lambda, this.ri, mround));
					this.stashedPrepare.putIfAbsent(mround, new ArrayList<>());
					List<ConsensusMessage> stashed = stashedPrepare.get(mround);
					stashed.add(message);
					yield new ArrayList<>();
				}
			}

			case COMMIT -> 
				commit(message);

			default -> {
				LOGGER.log(Level.INFO,
						MessageFormat.format("{0} - Received unknown message from {1}",
							config.getId(), message.getSenderId()));

				yield new ArrayList<>();
			}
		};
	}


	// TODO (dsa): add stuff for round change

	public void decide(String value) {
		if (this.decision.isPresent()) {

			if (!this.decision.get().equals(value)) {
				throw new RuntimeException(
					String.format(
						"QBFT decided two different values (%s and %s)",
						value,
						this.decision.get()));
			}

			return;
		}

		this.decision = Optional.of(value);

		for (Consumer obs: observers) {
			obs.accept(value);
		}
	}

	// TODO (dsa): factor out to schedule class (to test with different
	// schedules)
	private boolean isLeader(int lambda, int round, int id) {
		int leader = (lambda + round) % config.getN();
		return leader == id;
	}

	/**
	 * Returns node id
	 */
	public int getId() {
		return this.config.getId();
	}

	/**
	 * Returns whether input was already called
	 */
	public boolean hasStarted() {
		return started;
	}
}
