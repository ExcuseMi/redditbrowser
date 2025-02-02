package me.delta2force.redditbrowser.room;

import me.delta2force.redditbrowser.RedditBrowserPlugin;
import me.delta2force.redditbrowser.interaction.InteractiveEnum;
import me.delta2force.redditbrowser.room.comments.CommentsController;
import me.delta2force.redditbrowser.room.screen.ScreenController;
import me.delta2force.redditbrowser.room.screen.ScreenControllerFactory;
import me.delta2force.redditbrowser.room.screen.ScreenModelType;
import net.dean.jraw.ApiException;
import net.dean.jraw.models.Comment;
import net.dean.jraw.models.Submission;
import net.dean.jraw.tree.RootCommentNode;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static me.delta2force.redditbrowser.RedditBrowserPlugin.*;

public class Room {
    private static final float VOLUME = 75f;

    private static final String PREVIOUS_HOLOGRAM_NAME = colorCode("9") + "Previous";
    private static final Material ROOM_MATERIAL = Material.WHITE_WOOL;
    private static final String WRITE_COMMENT_HOLOGRAM = colorCode("9") + "Write comment";
    private static final String REPLY_COMMENT_HOLOGRAM = colorCode("9") + "Reply";

    public static final String COMMENT_DISPLAY_NAME = "Comment";
    public static final String NEWLINE = "\n";
    private final RedditBrowserPlugin redditBrowserPlugin;
    private final Player owner;
    private final Location location;
    private final RoomDimensions roomDimensions;
    private final ScreenController screenController;
    private RedditQueue redditQueue;
    private String subreddit;
    private Submission currentSubmission;

    public Room(
            RedditBrowserPlugin redditBrowserPlugin,
            Location location,
            String subreddit,
            RoomDimensions roomDimensions,
            Player owner) {
        this.redditBrowserPlugin = redditBrowserPlugin;
        this.owner = owner;
        this.location = location;
        this.roomDimensions = roomDimensions;
        final Location screenLocation = location.clone().add(
                -roomDimensions.getRoomWidth() + ((roomDimensions.getRoomWidth() - roomDimensions.getScreenHeight()) / 2) + 1,
                -2,
                -roomDimensions.getRoomDepth());
        final Location controlStationLocation = location.clone().add(-roomDimensions.getRoomWidth() + 2, -roomDimensions.getRoomHeight() + 1, -3);
        final Location commentControlStationLocation = location.clone().add(-roomDimensions.getRoomWidth() + 5, -roomDimensions.getRoomHeight(), -3);
        this.screenController = ScreenControllerFactory.create(
                this,
                screenLocation,
                controlStationLocation,
                commentControlStationLocation,
                roomDimensions.getScreenWidth(),
                roomDimensions.getScreenHeight());
        setSubReddit(subreddit);
    }

    public void build(Collection<Player> startingPlayers) {
        Bukkit.getScheduler().runTaskAsynchronously(redditBrowserPlugin, () -> {
            final Submission submission = redditQueue.next();
            if (submission != null) {
                currentSubmission = submission;
                Bukkit.getScheduler().runTask(redditBrowserPlugin, () -> {
                    createRoom(submission);

                    Bukkit.getScheduler().runTaskLater(redditBrowserPlugin, () -> {
                        setupPlayers(submission, startingPlayers);
                    }, 30);
                });
            } else {
                Bukkit.getScheduler().runTask(redditBrowserPlugin, () -> {
                    createRoom(null);
                    Bukkit.getScheduler().runTaskLater(redditBrowserPlugin, () -> {
                        setupPlayers(submission, startingPlayers);
                        startingPlayers.forEach(player -> player.sendMessage(ChatColor.RED + "No posts found."));

                    }, 30);
                });
            }
        });
    }

    public void addPlayer(Player player) {
        setupPlayers(currentSubmission, Arrays.asList(player));
    }

    public void refresh() {
        redditQueue.reset();
        screenController.clean();
        build(getPlayers());
    }

    public void updateSubreddit(String subreddit) {
        setSubReddit(subreddit);
        build(getPlayers());
    }

    private void setSubReddit(String subreddit) {
        this.subreddit = subreddit;
        redditQueue = new RedditQueue(redditBrowserPlugin.redditClient, subreddit);
    }

    private void setupPlayers(Submission submission, Collection<Player> startingPlayers) {
        Location loc = location.clone().add(-roomDimensions.getRoomWidth() / 2, -roomDimensions.getRoomHeight() + 1, -roomDimensions.getRoomDepth() / 2);
        loc.setPitch(0);
        loc.setYaw(180);
        loc.getChunk().load();
        location.clone().add(-roomDimensions.getRoomWidth(), -roomDimensions.getRoomHeight(), -roomDimensions.getRoomDepth()).getChunk().load();
        startingPlayers.forEach(player -> {
            // If it's pewds' subreddit, display LWIAY title
            if (subreddit.equalsIgnoreCase("pewdiepiesubmissions")) {
                player.sendTitle(colorCode("4") + "L" + colorCode("a") + "W" + colorCode("1") + "I" + colorCode("d") + "A" + colorCode("e") + "Y", "", 10, 70, 20);
            }
            player.teleport(loc);
            player.setGameMode(GameMode.SURVIVAL);
            cleanupInventory();
            if (submission != null) {
                updateTitleForPlayer(submission, player);
            }
        });
    }

    public static ItemStack createWritableBookStack() {
        final ItemStack itemStack = new ItemStack(Material.WRITABLE_BOOK);
        final ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setDisplayName(COMMENT_DISPLAY_NAME);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    private void updateTitleForRoom(Submission submission) {
        getPlayers().forEach(player -> updateTitleForPlayer(submission, player));
    }

    private void updateTitleForPlayer(Submission submission, Player player) {
        player.sendMessage(
                ChatColor.RESET + NEWLINE + NEWLINE +
                        ChatColor.DARK_BLUE +
                        "[" + getPostType(submission) + "] " +
                        ChatColor.WHITE + submission.getTitle()
                        + NEWLINE + ChatColor.GREEN +
                        ChatColor.ITALIC + " by /u/" + submission.getAuthor() + ChatColor.RESET
                        + NEWLINE + ChatColor.GOLD + "Karma : " + ChatColor.BOLD + submission.getScore() + NEWLINE + ChatColor.RESET);
    }

    private static String getPostType(Submission submission) {
        if (submission.isSelfPost()) {
            return "self";
        }
        return submission.getPostHint();
    }

    public void nextPost() {
        Bukkit.getScheduler().runTaskAsynchronously(redditBrowserPlugin, new Runnable() {
            @Override
            public void run() {
                final Submission submission = redditQueue.next();
                if (submission != null) {
                    currentSubmission = submission;

                    Bukkit.getScheduler().runTask(redditBrowserPlugin, new Runnable() {
                        @Override
                        public void run() {
                            updateRoom(submission);
                            updateTitleForRoom(submission);
                            cleanupInventory();
                        }
                    });
                } else {
                    getPlayers().forEach(player -> player.sendMessage(ChatColor.RED + "No more posts found."));
                }
            }
        });
    }

    private void cleanupInventory() {
        getPlayers().forEach(RedditBrowserPlugin::removeCommentsFromPlayerInventory);
    }

    public void previousPost() {
        Bukkit.getScheduler().runTaskAsynchronously(redditBrowserPlugin, () -> {
            final Submission submission = redditQueue.previous();
            currentSubmission = submission;
            if (submission != null) {
                Bukkit.getScheduler().runTask(redditBrowserPlugin, () -> {
                    updateRoom(submission);
                    updateTitleForRoom(submission);
                    cleanupInventory();
                });
            } else {
                getPlayers().forEach(player -> player.sendMessage(ChatColor.RED + "No previous posts found."));
            }
        });
    }

    private void updateRoom(Submission submission) {
        removeHologramByType(location, EntityType.ARMOR_STAND);
        emptyCommentsHopper();
        buildLeaveButton();
        removeNewCommentsButton();
        screenController.showPost(submission);
        removeHopper();
        if (submission != null) {
            buildNavigationButton();
            buildVoteButtons(submission);
            buildNewCommentButton();
            buildCommentHopper();
            buildRefreshButton();
            buildLeaveButton();
            buildSubredditHologram(submission);
        }
    }

    private void removeHopper() {
        Location chopperLocation = location.clone().add(-roomDimensions.getRoomWidth() / 2, -roomDimensions.getRoomHeight(), -roomDimensions.getRoomDepth() + 1);
        final Block hopper = chopperLocation.getBlock();
        hopper.setType(Material.AIR);
    }

    private void buildSubredditHologram(Submission submission) {
        final int height = roomDimensions.getRoomHeight() > 4 ? -(roomDimensions.getRoomHeight() / 2) : -1;
        spawnHologram(location.clone()
                .add(-(roomDimensions.getRoomWidth() / 2),
                        height,
                        -(roomDimensions.getRoomDepth() / 2))
                .clone().add(.5, -2, .5), colorCode("a") + "r/" + submission.getSubreddit());
    }


    private void buildRefreshButton() {
        Block refreshButton = location.getWorld().getBlockAt(location.clone().add(-(roomDimensions.getRoomWidth() / 2), -roomDimensions.getRoomHeight() + 2, -1));
        refreshButton.setType(Material.OAK_BUTTON);
        refreshButton.setMetadata(INTERACTIVE_ENUM, new FixedMetadataValue(redditBrowserPlugin, InteractiveEnum.REFRESH));
        refreshButton.setMetadata(ROOM_ID, new FixedMetadataValue(redditBrowserPlugin, owner.getUniqueId()));
        refreshButton.setMetadata(BUTTON_ACTIVATED, new FixedMetadataValue(redditBrowserPlugin, false));
        Directional refreshButtonDirectional = (Directional) refreshButton.getBlockData();
        refreshButtonDirectional.setFacing(BlockFace.NORTH);
        refreshButton.setBlockData(refreshButtonDirectional);
        spawnHologram(refreshButton.getLocation().clone().add(.5, -2, .5), colorCode("a") + "Refresh");
    }

    private void buildNewCommentButton() {
        Location buttonLocation = location.clone().add(-roomDimensions.getRoomWidth() / 2 - 1, -roomDimensions.getRoomHeight() + 1, -roomDimensions.getRoomDepth() + 1);
        final Block writeCommentsButton = buttonLocation.getBlock();
        writeCommentsButton.setType(Material.OAK_BUTTON);
        writeCommentsButton.setMetadata(INTERACTIVE_ENUM, new FixedMetadataValue(redditBrowserPlugin, InteractiveEnum.WRITE_COMMENT));
        writeCommentsButton.setMetadata(ROOM_ID, new FixedMetadataValue(redditBrowserPlugin, owner.getUniqueId()));
        writeCommentsButton.setMetadata(BUTTON_ACTIVATED, new FixedMetadataValue(redditBrowserPlugin, false));

        Directional commentsButtonDirection = (Directional) writeCommentsButton.getBlockData();
        commentsButtonDirection.setFacing(BlockFace.SOUTH);
        writeCommentsButton.setBlockData(commentsButtonDirection);

        spawnHologram(writeCommentsButton.getLocation().clone().add(.5, -2, .5), WRITE_COMMENT_HOLOGRAM);
    }

    private void buildCommentHopper() {
        Location chopperLocation = location.clone().add(-roomDimensions.getRoomWidth() / 2, -roomDimensions.getRoomHeight(), -roomDimensions.getRoomDepth() + 1);
        final Block hopper = chopperLocation.getBlock();
        hopper.setType(Material.HOPPER);
        hopper.setMetadata(INTERACTIVE_ENUM, new FixedMetadataValue(redditBrowserPlugin, InteractiveEnum.COMMENT_HOPPER));
        hopper.setMetadata(ROOM_ID, new FixedMetadataValue(redditBrowserPlugin, owner.getUniqueId()));
        spawnHologram(hopper.getLocation().clone().add(.5, -2, .5), REPLY_COMMENT_HOLOGRAM);
    }


    private void buildLeaveButton() {
        Block leaveButton = location.getWorld().getBlockAt(location.clone().add(-(roomDimensions.getRoomWidth() / 2) - 1, -roomDimensions.getRoomHeight() + 2, -1));
        leaveButton.setType(Material.OAK_BUTTON);
        leaveButton.setMetadata(INTERACTIVE_ENUM, new FixedMetadataValue(redditBrowserPlugin, InteractiveEnum.LEAVE));
        leaveButton.setMetadata(ROOM_ID, new FixedMetadataValue(redditBrowserPlugin, owner.getUniqueId()));
        leaveButton.setMetadata(BUTTON_ACTIVATED, new FixedMetadataValue(redditBrowserPlugin, false));
        Directional leaveButtonDirectional = (Directional) leaveButton.getBlockData();
        leaveButtonDirectional.setFacing(BlockFace.NORTH);
        leaveButton.setBlockData(leaveButtonDirectional);
        spawnHologram(leaveButton.getLocation().clone().add(.5, -2, .5), colorCode("c") + "Leave");
    }

    private void emptyCommentsHopper() {
        Location chestLocation = location.clone().add(-roomDimensions.getRoomWidth() / 2, -roomDimensions.getRoomHeight(), -roomDimensions.getRoomDepth() + 1);
        final Block block = chestLocation.getBlock();
        if (Material.HOPPER.equals(block.getType())) {
            Hopper hopper = (Hopper) block.getState();
            hopper.getInventory().clear();
        }
    }

    private void buildEmptyRoom() {
        emptyCommentsHopper();
        cube(ROOM_MATERIAL, location, location.clone().add(-roomDimensions.getRoomWidth(), -roomDimensions.getRoomHeight(), -roomDimensions.getRoomDepth()));
        cube(Material.AIR, location.clone().add(-1, -1, -1), location.clone().add(-roomDimensions.getRoomWidth() + 1, -roomDimensions.getRoomHeight() + 1, -roomDimensions.getRoomDepth() + 1));
    }

    private void createRoom(
            Submission submission) {
        buildEmptyRoom();
        updateRoom(submission);
    }

    private void removeNewCommentsButton() {
        Location buttonLocation = location.clone().add(-roomDimensions.getRoomWidth() / 2 - 1, -roomDimensions.getRoomHeight() + 1, -roomDimensions.getRoomDepth() + 1);
        final Block writeCommentsButton = buttonLocation.getBlock();
        writeCommentsButton.setType(Material.AIR);
    }

    private void buildVoteButtons(Submission submission) {
        Block uv = location.getWorld().getBlockAt(location.clone().add(-roomDimensions.getRoomWidth() + 1, -roomDimensions.getRoomHeight() + 1, -roomDimensions.getRoomDepth() + 1));
        uv.setType(Material.OAK_BUTTON);
        uv.setMetadata(SUBMISSION_ID, new FixedMetadataValue(redditBrowserPlugin, submission.getId()));
        uv.setMetadata(ROOM_ID, new FixedMetadataValue(redditBrowserPlugin, getRoomId()));
        uv.setMetadata(INTERACTIVE_ENUM, new FixedMetadataValue(redditBrowserPlugin, InteractiveEnum.UPVOTE));
        Directional uvdir = (Directional) uv.getBlockData();
        uvdir.setFacing(BlockFace.SOUTH);
        uv.setBlockData(uvdir);
        spawnHologram(uv.getLocation().clone().add(.5, -2, .5), colorCode("a") + "+1");


        Block dv = location.getWorld().getBlockAt(location.clone().add(-1, -roomDimensions.getRoomHeight() + 1, -roomDimensions.getRoomDepth() + 1));
        dv.setType(Material.OAK_BUTTON);
        dv.setMetadata(SUBMISSION_ID, new FixedMetadataValue(redditBrowserPlugin, submission.getId()));
        dv.setMetadata(ROOM_ID, new FixedMetadataValue(redditBrowserPlugin, getRoomId()));
        dv.setMetadata(INTERACTIVE_ENUM, new FixedMetadataValue(redditBrowserPlugin, InteractiveEnum.DOWNVOTE));


        Directional dvdir = (Directional) dv.getBlockData();
        dvdir.setFacing(BlockFace.SOUTH);
        dv.setBlockData(dvdir);

        spawnHologram(dv.getLocation().clone().add(.5, -2, .5), colorCode("c") + "-1");
    }


    private void buildNavigationButton() {
        int zPosition = (-roomDimensions.getRoomDepth() / 2) + 1;
        if (zPosition > -2) {
            zPosition = -2;
        }

        Block nextButton = location.getWorld().getBlockAt(location.clone().add(-1, -roomDimensions.getRoomHeight() + 2, zPosition));
        nextButton.setType(Material.OAK_BUTTON);
        nextButton.setMetadata(INTERACTIVE_ENUM, new FixedMetadataValue(redditBrowserPlugin, InteractiveEnum.NEXT_ROOM));
        nextButton.setMetadata(BUTTON_ACTIVATED, new FixedMetadataValue(redditBrowserPlugin, false));
        nextButton.setMetadata(ROOM_ID, new FixedMetadataValue(redditBrowserPlugin, owner.getUniqueId()));
        Directional nextButtonDirection = (Directional) nextButton.getBlockData();
        nextButtonDirection.setFacing(BlockFace.WEST);
        nextButton.setBlockData(nextButtonDirection);
        spawnHologram(nextButton.getLocation().clone().add(.5, -2, .5), colorCode("9") + "Next");

        if (redditQueue.hasPrevious()) {
            Block previousButton = location.getWorld().getBlockAt(location.clone().add(-roomDimensions.getRoomWidth() + 1, -roomDimensions.getRoomHeight() + 2, zPosition));
            previousButton.setType(Material.OAK_BUTTON);
            previousButton.setMetadata(INTERACTIVE_ENUM, new FixedMetadataValue(redditBrowserPlugin, InteractiveEnum.PREVIOUS_ROOM));
            previousButton.setMetadata(BUTTON_ACTIVATED, new FixedMetadataValue(redditBrowserPlugin, false));
            previousButton.setMetadata(ROOM_ID, new FixedMetadataValue(redditBrowserPlugin, owner.getUniqueId()));
            Directional previousButtonDirection = (Directional) nextButton.getBlockData();
            previousButtonDirection.setFacing(BlockFace.EAST);
            previousButton.setBlockData(previousButtonDirection);
            spawnHologram(previousButton.getLocation().clone().add(.5, -2, .5), PREVIOUS_HOLOGRAM_NAME);
        } else {
            Block previousButton = location.getWorld().getBlockAt(location.clone().add(-roomDimensions.getRoomWidth() + 1, -roomDimensions.getRoomHeight() + 2, zPosition));
            previousButton.setType(Material.AIR);
            removeHologramByName(previousButton.getLocation().clone().add(.5, -2, .5), PREVIOUS_HOLOGRAM_NAME);
        }
    }

    private void removeHologramByName(Location location, String name) {
        location.getWorld()
                .getNearbyEntities(location,
                        -roomDimensions.getRoomWidth(),
                        -roomDimensions.getRoomHeight(),
                        -roomDimensions.getRoomDepth(),
                        o -> Objects.equals(name, o.getName()))
                .forEach(Entity::remove);
    }

    void removeHologramByType(Location location, EntityType entityType) {
        location.getWorld()
                .getNearbyEntities(location,
                        -roomDimensions.getRoomWidth(),
                        -roomDimensions.getRoomHeight(),
                        -roomDimensions.getRoomDepth(),
                        o -> Objects.equals(entityType, o.getType()))
                .forEach(Entity::remove);
    }

    private void cube(Material blockMaterial, Location from, Location to) {
        for (int x = from.getBlockX(); x >= to.getBlockX(); x--) {
            for (int y = from.getBlockY(); y >= to.getBlockY(); y--) {
                for (int z = from.getBlockZ(); z >= to.getBlockZ(); z--) {
                    final Block block = from.getWorld().getBlockAt(x, y, z);
                    block.setType(blockMaterial);
                }
            }
        }
    }

    public static void spawnHologram(Location location, String name) {
        ArmorStand as = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
        as.setCustomName(name);
        as.setCustomNameVisible(true);
        as.setGravity(false);
        as.setVisible(false);
        as.setInvulnerable(true);
        as.setCollidable(false);
    }

    public boolean hasPlayers() {
        return location.getWorld().getNearbyEntities(location,
                -roomDimensions.getRoomWidth(),
                -roomDimensions.getRoomHeight(),
                -roomDimensions.getRoomDepth(),
                entity -> entity instanceof Player).size() > 0;
    }

    public boolean hasPlayer(UUID playerId) {
        return location.getWorld().getNearbyEntities(location,
                -roomDimensions.getRoomWidth(),
                -roomDimensions.getRoomHeight(),
                -roomDimensions.getRoomDepth(),
                entity -> entity instanceof Player)
                .stream()
                .anyMatch(o -> Objects.equals(playerId, o.getUniqueId()));
    }

    public Set<Player> getPlayers() {
        return location.getWorld().getNearbyEntities(location,
                -roomDimensions.getRoomWidth(),
                -roomDimensions.getRoomHeight(),
                -roomDimensions.getRoomDepth(),
                entity -> entity instanceof Player)
                .stream()
                .map(o -> (Player) o)
                .collect(Collectors.toSet());
    }

    public void destroy() {
        try {
            Bukkit.getScheduler().runTask(redditBrowserPlugin, new Runnable() {
                @Override
                public void run() {
                    location.getWorld().getNearbyEntities(location,
                            -roomDimensions.getRoomWidth(),
                            -roomDimensions.getRoomHeight(),
                            -roomDimensions.getRoomDepth(),
                            entity -> !(entity instanceof Player))
                            .forEach(Entity::remove);
                    getPlayers().forEach(redditBrowserPlugin::kickOut);
                    cube(Material.AIR, location, location.clone().add(-roomDimensions.getRoomWidth(), -roomDimensions.getRoomHeight(), -roomDimensions.getRoomDepth()));
                    Bukkit.getLogger().log(Level.INFO, "Destroyed reddit room of player " + owner.getDisplayName());
                }
            });
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.INFO, "Could not remove reddit room of player " + owner.getDisplayName());
        }
    }

    public UUID getRoomId() {
        return owner.getUniqueId();
    }

    public boolean isInside(Location locationToTest) {
        Location roomMin = this.location.clone().add(-roomDimensions.getRoomWidth(), -roomDimensions.getRoomHeight(), -roomDimensions.getRoomDepth());
        Location roomMax = this.location;
        if (!Objects.equals(roomMax.getWorld().getName(), locationToTest.getWorld().getName())) {
            return false;
        }
        boolean x = locationToTest.getX() >= Math.min(roomMax.getX(), roomMin.getX()) && locationToTest.getX() <= Math.max(roomMax.getX(), roomMin.getX());
        boolean y = locationToTest.getY() >= Math.min(roomMax.getY(), roomMin.getY()) && locationToTest.getY() <= Math.max(roomMax.getY(), roomMin.getY());
        boolean z = locationToTest.getZ() >= Math.min(roomMax.getZ(), roomMin.getZ()) && locationToTest.getZ() <= Math.max(roomMax.getZ(), roomMin.getZ());
        return x && y && z;
    }

    public void showURLtoPlayers(Player player) {
        if (currentSubmission != null) {
            player.sendMessage(ChatColor.YELLOW + "Image/Video link: " + ChatColor.BLUE + ChatColor.UNDERLINE + currentSubmission.getUrl());
        }
    }

    public Material getRoomMaterial() {
        return ROOM_MATERIAL;
    }

    public RedditBrowserPlugin getRedditBrowserPlugin() {
        return redditBrowserPlugin;
    }

    public ScreenController getScreenController() {
        return screenController;
    }

    public void showPost() {
        if (currentSubmission != null) {
            Bukkit.getScheduler().runTaskAsynchronously(redditBrowserPlugin, () -> {
                Bukkit.getScheduler().runTask(
                        redditBrowserPlugin,
                        () -> {
                            screenController.showPost(currentSubmission);
                        });
            });
        }
    }

    public void showComment() {
        if (currentSubmission != null) {
            Bukkit.getScheduler().runTaskAsynchronously(redditBrowserPlugin, () -> {
                final RootCommentNode comments = redditBrowserPlugin.redditClient
                        .submission(currentSubmission.getId())
                        .comments();
                Bukkit.getScheduler().runTask(
                        redditBrowserPlugin,
                        () -> screenController.getCommentsController().updateComments(comments != null ? comments.getReplies() : Collections.emptyList()));
            });
        }
    }

    public CommentsController getCommentsController() {
        return screenController.getCommentsController();
    }

    public void replyComment(String comment) {
        if (ScreenModelType.POST.equals(getScreenController().getScreenModelType())) {
            if (currentSubmission != null) {
                final String currentSubmissionId = currentSubmission.getId();
                Bukkit.getScheduler().runTaskAsynchronously(redditBrowserPlugin, () -> {
                    try {
                        redditBrowserPlugin.redditClient
                                .submission(currentSubmissionId)
                                .reply(comment);
                        getPlayers().forEach(p -> p.sendMessage("You've replied to the post!"));
                    } catch (ApiException apiException) {
                        getPlayers().forEach(p->p.sendMessage("Error occurred: " + apiException.getExplanation()));
                    }
                });
            }
        } else if (ScreenModelType.COMMENT.equals(getScreenController().getScreenModelType())) {
            final Comment current = getCommentsController().getCurrent();
            if (current != null) {
                final String currentId = current.getId();
                Bukkit.getScheduler().runTaskAsynchronously(redditBrowserPlugin, () -> {
                    try {
                        redditBrowserPlugin.redditClient.comment(currentId).reply(comment);
                        getPlayers().forEach(p -> p.sendMessage("You've replied to the comment!"));
                    } catch (ApiException apiException) {
                        getPlayers().forEach(p->p.sendMessage("Error occurred: " + apiException.getExplanation()));
                    }
                });
            }
        }
    }

    public void upvote() {
        if (ScreenModelType.POST.equals(getScreenController().getScreenModelType())) {
            if (currentSubmission != null) {
                final String currentSubmissionId = currentSubmission.getId();
                Bukkit.getScheduler().runTaskAsynchronously(redditBrowserPlugin, () -> {
                    redditBrowserPlugin.redditClient.submission(currentSubmissionId).upvote();
                    int karma = redditBrowserPlugin.redditClient.submission(currentSubmissionId).inspect().getScore();
                    getPlayers().forEach(player -> {
                        player.sendMessage(ChatColor.GREEN + "You have upvoted the post! It now has " + karma + " karma.");
                        player.playSound(location, Sound.ENTITY_VILLAGER_YES, VOLUME, 1);
                    });
                });
            }
        } else if (ScreenModelType.COMMENT.equals(getScreenController().getScreenModelType())) {
            final Comment current = getCommentsController().getCurrent();
            if (current != null) {
                final String currentId = current.getId();
                Bukkit.getScheduler().runTaskAsynchronously(redditBrowserPlugin, () -> {
                    redditBrowserPlugin.redditClient.comment(currentId).upvote();
                    getPlayers().forEach(player -> {
                        player.sendMessage(ChatColor.GREEN + "You have upvoted the comment!");
                        player.playSound(location, Sound.ENTITY_VILLAGER_YES, VOLUME, 1);
                    });
                });
            }
        }
    }

    public void downvote() {
        if (ScreenModelType.POST.equals(getScreenController().getScreenModelType())) {
            if (currentSubmission != null) {
                final String currentSubmissionId = currentSubmission.getId();
                Bukkit.getScheduler().runTaskAsynchronously(redditBrowserPlugin, () -> {
                    redditBrowserPlugin.redditClient.submission(currentSubmissionId).downvote();
                    int karma = redditBrowserPlugin.redditClient.submission(currentSubmissionId).inspect().getScore();
                    getPlayers().forEach(player -> {
                        player.sendMessage(ChatColor.RED + "You have downvoted the post! It now has " + karma + " karma.");
                        player.playSound(location, Sound.ENTITY_VILLAGER_NO, VOLUME, 1);
                    });
                });
            }
        } else if (ScreenModelType.COMMENT.equals(getScreenController().getScreenModelType())) {
            final Comment current = getCommentsController().getCurrent();
            if (current != null) {
                final String currentId = current.getId();
                Bukkit.getScheduler().runTaskAsynchronously(redditBrowserPlugin, () -> {
                    redditBrowserPlugin.redditClient.comment(currentId).upvote();
                    getPlayers().forEach(player -> {
                        player.sendMessage(ChatColor.RED + "You have downvoted the comment!");
                        player.playSound(location, Sound.ENTITY_VILLAGER_NO, VOLUME, 1);
                    });
                });
            }
        }
    }
}
