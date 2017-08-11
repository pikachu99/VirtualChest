package com.github.ustc_zzzz.virtualchest.action;

import com.github.ustc_zzzz.virtualchest.VirtualChestPlugin;
import com.github.ustc_zzzz.virtualchest.economy.VirtualChestEconomyManager;
import com.github.ustc_zzzz.virtualchest.placeholder.VirtualChestPlaceholderManager;
import com.github.ustc_zzzz.virtualchest.unsafe.SpongeUnimplemented;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.ItemStackSnapshot;
import org.spongepowered.api.network.ChannelBinding;
import org.spongepowered.api.network.ChannelRegistrar;
import org.spongepowered.api.scheduler.SpongeExecutorService;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.chat.ChatTypes;
import org.spongepowered.api.text.serializer.TextSerializers;
import org.spongepowered.api.text.title.Title;
import org.spongepowered.api.util.Tuple;

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author ustc_zzzz
 */
public final class VirtualChestActions
{
    private static final char PREFIX_SPLITTER = ':';

    private final VirtualChestPlugin plugin;
    private final Map<String, VirtualChestActionExecutor> executors = new HashMap<>();
    private final Map<Player, LinkedList<Tuple<String, String>>> playersInAction = new WeakHashMap<>();
    private final Map<Player, Callback> playerCallbacks = new WeakHashMap<>();
    private final SpongeExecutorService executorService;

    private ChannelBinding.RawDataChannel bungeeCordChannel;

    public VirtualChestActions(VirtualChestPlugin plugin)
    {
        this.plugin = plugin;
        this.executorService = Sponge.getScheduler().createSyncExecutor(plugin);

        registerPrefix("console", this::processConsole);
        registerPrefix("tell", this::processTell);
        registerPrefix("tellraw", this::processTellraw);
        registerPrefix("broadcast", this::processBroadcast);
        registerPrefix("title", this::processTitle);
        registerPrefix("bigtitle", this::processBigtitle);
        registerPrefix("subtitle", this::processSubtitle);
        registerPrefix("delay", this::processDelay);
        registerPrefix("connect", this::processConnect);
        registerPrefix("cost", this::processCost);
        registerPrefix("cost-item", this::processCostItem);

        registerPrefix("", this::process);

        TitleManager.enable(plugin);
    }

    public void init()
    {
        ChannelRegistrar channelRegistrar = Sponge.getChannelRegistrar();
        this.bungeeCordChannel = channelRegistrar.getOrCreateRaw(this.plugin, "BungeeCord");
    }

    public void registerPrefix(String prefix, VirtualChestActionExecutor executor)
    {
        this.executors.put(prefix, executor);
    }

    public boolean isPlayerInAction(Player player)
    {
        return this.playersInAction.containsKey(player);
    }

    public void submitCommands(Player player, List<String> commands)
    {
        plugin.getLogger().debug("Player {} tries to run {}", player, commands);
        VirtualChestPlaceholderManager placeholderManager = this.plugin.getPlaceholderManager();
        LinkedList<Tuple<String, String>> commandList = new LinkedList<>();
        for (String command : commands)
        {
            int colonPos = command.indexOf(PREFIX_SPLITTER);
            String prefix = colonPos > 0 ? command.substring(0, colonPos) : "";
            if (this.executors.containsKey(prefix))
            {
                int length = command.length(), suffixPosition = colonPos + 1;
                while (suffixPosition < length && Character.isWhitespace(command.charAt(suffixPosition)))
                {
                    ++suffixPosition;
                }
                String suffix = command.substring(suffixPosition);
                commandList.add(Tuple.of(prefix, placeholderManager.parseAction(player, suffix)));
            }
            else if (!command.isEmpty())
            {
                commandList.add(Tuple.of("", placeholderManager.parseAction(player, command)));
            }
        }
        this.executorService.submit(() -> new Callback(player, commandList).accept(CommandResult.empty()));
    }

    private void process(Player player, String command, Consumer<CommandResult> callback)
    {
        callback.accept(Sponge.getCommandManager().process(player, command));
    }

    private void processCostItem(Player player, String command, Consumer<CommandResult> callback)
    {
        int count = Integer.parseInt(command.replaceFirst("\\s++$", ""));
        ItemStack stackUsed = SpongeUnimplemented.getItemHeldByMouse(player).createStack();
        int stackUsedQuantity = stackUsed.getQuantity();
        if (stackUsedQuantity > count)
        {
            stackUsed.setQuantity(stackUsedQuantity - count);
            SpongeUnimplemented.setItemHeldByMouse(player, stackUsed.createSnapshot());
            callback.accept(CommandResult.success());
        }
        else
        {
            SpongeUnimplemented.setItemHeldByMouse(player, ItemStackSnapshot.NONE);
            callback.accept(CommandResult.empty());
        }
    }

    private void processCost(Player player, String command, Consumer<CommandResult> callback)
    {
        int index = command.lastIndexOf(':');
        String currencyName = index < 0 ? "" : command.substring(0, index).toLowerCase();
        BigDecimal cost = new BigDecimal(command.substring(index + 1).replaceFirst("\\s++$", ""));
        VirtualChestEconomyManager economyManager = this.plugin.getEconomyManager();
        if (cost.signum() > 0)
        {
            boolean result = economyManager.withdrawBalance(currencyName, player, cost, false);
            callback.accept(result ? CommandResult.success() : CommandResult.empty());
        }
        else
        {
            boolean result = economyManager.depositBalance(currencyName, player, cost.negate(), false);
            callback.accept(result ? CommandResult.success() : CommandResult.empty());
        }
    }

    private void processConnect(Player player, String command, Consumer<CommandResult> callback)
    {
        this.bungeeCordChannel.sendTo(player, buf ->
        {
            buf.writeUTF("Connect");
            buf.writeUTF(command.replaceFirst("\\s++$", ""));
        });
        callback.accept(CommandResult.success());
    }

    private void processDelay(Player player, String command, Consumer<CommandResult> callback)
    {
        try
        {
            int delayTick = Integer.parseInt(command.replaceFirst("\\s++$", ""));
            if (delayTick <= 0)
            {
                throw new NumberFormatException();
            }
            Runnable taskExecutor = () -> callback.accept(CommandResult.success());
            this.executorService.schedule(taskExecutor, 50 * delayTick - 1, TimeUnit.MILLISECONDS);
        }
        catch (NumberFormatException e)
        {
            callback.accept(CommandResult.empty());
        }
    }

    private void processBigtitle(Player player, String command, Consumer<CommandResult> callback)
    {
        Text text = TextSerializers.FORMATTING_CODE.deserialize(command);
        TitleManager.pushBigtitle(text, player);
        callback.accept(CommandResult.success());
    }

    private void processSubtitle(Player player, String command, Consumer<CommandResult> callback)
    {
        Text text = TextSerializers.FORMATTING_CODE.deserialize(command);
        TitleManager.pushSubtitle(text, player);
        callback.accept(CommandResult.success());
    }

    private void processTitle(Player player, String command, Consumer<CommandResult> callback)
    {
        Text text = TextSerializers.FORMATTING_CODE.deserialize(command);
        player.sendMessage(ChatTypes.ACTION_BAR, text);
        callback.accept(CommandResult.success());
    }

    private void processBroadcast(Player player, String command, Consumer<CommandResult> callback)
    {
        Text text = TextSerializers.FORMATTING_CODE.deserialize(command);
        Sponge.getServer().getBroadcastChannel().send(text);
        callback.accept(CommandResult.success());
    }

    private void processTellraw(Player player, String command, Consumer<CommandResult> callback)
    {
        player.sendMessage(TextSerializers.JSON.deserialize(command));
        callback.accept(CommandResult.success());
    }

    private void processTell(Player player, String command, Consumer<CommandResult> callback)
    {
        player.sendMessage(TextSerializers.FORMATTING_CODE.deserialize(command));
        callback.accept(CommandResult.success());
    }

    private void processConsole(Player player, String command, Consumer<CommandResult> callback)
    {
        callback.accept(Sponge.getCommandManager().process(Sponge.getServer().getConsole(), command));
    }

    private class Callback implements Consumer<CommandResult>
    {
        private final WeakReference<Player> player;

        private Callback(Player p, LinkedList<Tuple<String, String>> commandList)
        {
            player = new WeakReference<>(p);
            playersInAction.put(p, commandList);
            playerCallbacks.put(p, this);
        }

        @Override
        public void accept(CommandResult commandResult)
        {
            Optional<Player> playerOptional = Optional.ofNullable(player.get());
            if (playerOptional.isPresent())
            {
                Player p = playerOptional.get();
                LinkedList<Tuple<String, String>> commandList = playersInAction.getOrDefault(p, new LinkedList<>());
                if (commandList.isEmpty())
                {
                    playersInAction.remove(p);
                    playerCallbacks.remove(p);
                }
                else
                {
                    Tuple<String, String> t = commandList.pop();
                    Optional<Callback> callbackOptional = Optional.ofNullable(playerCallbacks.get(p));
                    callbackOptional.ifPresent(c ->
                    {
                        String command = t.getFirst().isEmpty() ? t.getSecond() : t.getFirst() + ": " + t.getSecond();
                        plugin.getLogger().debug("Player {}, is now executing {}", p.getName(), command);
                        executors.get(t.getFirst()).doAction(p, t.getSecond(), c);
                    });
                }
            }
        }
    }

    private static class TitleManager
    {
        private static final Map<Player, Text> BIGTITLES = new WeakHashMap<>();
        private static final Map<Player, Text> SUBTITLES = new WeakHashMap<>();

        private static Task task;

        private static void sendTitle(Task task)
        {
            Map<Player, Title.Builder> builderMap = new HashMap<>();
            for (Map.Entry<Player, Text> entry : BIGTITLES.entrySet())
            {
                builderMap.compute(entry.getKey(), (player, builder) ->
                        Optional.ofNullable(builder).orElseGet(Title::builder).title(entry.getValue()));
            }
            BIGTITLES.clear();
            for (Map.Entry<Player, Text> entry : SUBTITLES.entrySet())
            {
                builderMap.compute(entry.getKey(), (player, builder) ->
                        Optional.ofNullable(builder).orElseGet(Title::builder).subtitle(entry.getValue()));
            }
            SUBTITLES.clear();
            builderMap.forEach((player, builder) -> player.sendTitle(builder.build()));
        }

        private static void pushBigtitle(Text title, Player player)
        {
            BIGTITLES.put(player, title);
        }

        private static void pushSubtitle(Text title, Player player)
        {
            SUBTITLES.put(player, title);
        }

        private static void enable(Object plugin)
        {
            Optional.ofNullable(task).ifPresent(Task::cancel);
            Task.Builder builder = Sponge.getScheduler().createTaskBuilder().intervalTicks(1);
            task = builder.name("VirtualChestTitleManager").execute(TitleManager::sendTitle).submit(plugin);
        }
    }
}
