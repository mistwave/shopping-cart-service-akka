package shopping.cart;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import akka.pattern.StatusReply;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.*;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** @author yifuduan on 2022/1/21 */
public class ShoppingCart
    extends EventSourcedBehaviorWithEnforcedReplies<
        ShoppingCart.Command, ShoppingCart.Event, ShoppingCart.State> {

  static final EntityTypeKey<Command> ENTITY_KEY =
      EntityTypeKey.create(Command.class, "ShoppingCart");

  private final String cartId;

  public static void init(ActorSystem<?> system) {
    ClusterSharding.get(system)
        .init(
            Entity.of(
                ENTITY_KEY, entityContext -> ShoppingCart.create(entityContext.getEntityId())));
  }

  public static Behavior<Command> create(String cartId) {
    return Behaviors.setup(ctx -> EventSourcedBehavior.start(new ShoppingCart(cartId), ctx));
  }

  @Override
  public RetentionCriteria retentionCriteria() {
    return RetentionCriteria.snapshotEvery(100, 3);
  }

  private ShoppingCart(String cartId) {
    super(
        PersistenceId.of(ENTITY_KEY.name(), cartId),
        SupervisorStrategy.restartWithBackoff(Duration.ofMillis(200), Duration.ofSeconds(5), 0.1));
    this.cartId = cartId;
  }

  @Override
  public State emptyState() {
    return new State();
  }

  @Override
  public EventHandler<State, Event> eventHandler() {
    return newEventHandlerBuilder()
        .forAnyState()
        .onEvent(ItemAdded.class, (state, evt) -> state.updateItem(evt.itemId, evt.quantity))
        .build();
  }

  @Override
  public CommandHandlerWithReply<Command, Event, State> commandHandler() {
    CommandHandlerWithReplyBuilder<Command, Event, State> builder =
        newCommandHandlerWithReplyBuilder();

    builder.forAnyState().onCommand(AddItem.class, this::onAddItem);
    return builder.build();
  }

  private ReplyEffect<Event, State> onAddItem(State state, AddItem cmd) {
    if (state.hasItem(cmd.itemId)) {
      return Effect()
          .reply(
              cmd.replyTo,
              StatusReply.error("Item '" + cmd.itemId + "' was already added to this cart"));
    } else if (cmd.quantity <= 0) {
      return Effect().reply(cmd.replyTo, StatusReply.error("Quantity must be greater than zero"));
    } else {
      return Effect()
          .persist(new ItemAdded(cartId, cmd.itemId, cmd.quantity))
          .thenReply(cmd.replyTo, updatedCart -> StatusReply.success(updatedCart.toSummary()));
    }
  }

  interface Command extends CborSerializable {}

  public static final class AddItem implements Command {
    final String itemId;
    final int quantity;
    final ActorRef<StatusReply<Summary>> replyTo;

    public AddItem(String itemId, int quantity, ActorRef<StatusReply<Summary>> replyTo) {
      this.itemId = itemId;
      this.quantity = quantity;
      this.replyTo = replyTo;
    }
  }

  public static final class Summary implements CborSerializable {
    final Map<String, Integer> items;

    @JsonCreator
    public Summary(Map<String, Integer> items) {
      this.items = new HashMap<>(items);
    }
  }

  abstract static class Event implements CborSerializable {
    public final String cartId;

    public Event(String cartId) {
      this.cartId = cartId;
    }
  }

  static final class ItemAdded extends Event {
    public final String itemId;
    public final int quantity;

    public ItemAdded(String cartId, String itemId, int quantity) {
      super(cartId);
      this.itemId = itemId;
      this.quantity = quantity;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ItemAdded itemAdded = (ItemAdded) o;

      if (quantity != itemAdded.quantity) return false;
      return Objects.equals(itemId, itemAdded.itemId);
    }

    @Override
    public int hashCode() {
      int result = itemId != null ? itemId.hashCode() : 0;
      result = 31 * result + quantity;
      return result;
    }
  }

  static final class State implements CborSerializable {
    final Map<String, Integer> items;

    public State() {
      this(new HashMap<>());
    }

    public State(Map<String, Integer> items) {
      this.items = items;
    }

    public boolean hasItem(String itemId) {
      return items.containsKey(itemId);
    }

    public State updateItem(String itemId, int quantity) {
      if (quantity == 0) {
        items.remove(itemId);
      } else {
        items.put(itemId, quantity);
      }

      return this;
    }

    public Summary toSummary() {
      return new Summary(items);
    }

    public int itemCount(String itemId) {
      return items.get(itemId);
    }

    public boolean isEmpty() {
      return items.isEmpty();
    }
  }
}
