package com.minecolonies.entity.ai;

import com.minecolonies.colony.jobs.JobLumberjack;
import com.minecolonies.entity.pathfinding.PathJobFindTree;
import com.minecolonies.util.BlockPosUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSapling;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.minecolonies.entity.ai.AIState.*;

/**
 * The lumberjack AI class
 */
public class EntityAIWorkLumberjack extends AbstractEntityAIWork<JobLumberjack>
{
    private static final String TOOL_TYPE_AXE           = "axe";
    /**
     * The render name to render logs
     */
    private static final String RENDER_META_LOGS        = "Logs";
    /**
     * The range in which the lumberjack searches for trees.
     */
    private static final int    SEARCH_RANGE            = 50;
    /**
     * If no trees are found, increment the range
     */
    private static final int    SEARCH_INCREMENT        = 5;
    /**
     * If this limit is reached, no trees are found.
     */
    private static final int    SEARCH_LIMIT            = 150;
    /**
     * Number of ticks to wait before coming
     * to the conclusion of being stuck
     */
    private static final int    STUCK_WAIT_TIME         = 10;
    /**
     * Number of ticks until he gives up destroying leaves
     * and walks a bit back to try a new path
     */
    private static final int    WALKING_BACK_WAIT_TIME  = 60;
    /**
     * How much he backs away when really not finding any path
     */
    private static final double WALK_BACK_RANGE         = 3.0;
    /**
     * The speed in which he backs away
     */
    private static final double WALK_BACK_SPEED         = 1.0;
    /**
     * Time in ticks to wait before placing a sapling.
     * Is used to collect falling saplings from the ground.
     */
    private static final int    WAIT_BEFORE_SAPLING     = 100;
    /**
     * Time in ticks to wait before rechecking
     * if there are trees in the
     * range of the lumberjack
     */
    private static final int    WAIT_BEFORE_SEARCH      = 100;
    /**
     * Time in ticks before incrementing the search radius.
     */
    private static final int    WAIT_BEFORE_INCREMENT   = 20;
    /**
     * The amount of time to wait while walking to items
     */
    private static final int    WAIT_WHILE_WALKING      = 5;
    /**
     * Horizontal range in which the lumberjack picks up items
     */
    private static final float  RANGE_HORIZONTAL_PICKUP = 45.0F;
    /**
     * Vertical range in which the lumberjack picks up items
     */
    private static final float  RANGE_VERTICAL_PICKUP   = 15.0F;
    /**
     * Number of ticks the lumberjack is standing still
     */
    private              int    stillTicks              = 0;
    /**
     * Used to store the walk distance
     * to check if the lumberjack is still walking
     */
    private              int    previousDistance        = 0;
    /**
     * Used to store the path index
     * to check if the lumberjack is still walking
     */
    private              int    previousIndex           = 0;
    /**
     * Positions of all items that have to be collected.
     */
    private List<BlockPos>                 items;
    /**
     * The active pathfinding job used to walk to trees
     */
    private PathJobFindTree.TreePathResult pathResult;
    /**
     * Lumberjack woodcutting experience
     * todo: enable usage of this
     */
    private int woodCuttingSkill = worker.getStrength() * worker.getCharisma() * (worker.getExperienceLevel() + 1);
    /**
     * A counter by how much the tree search radius
     * has been increased by now.
     */
    private int searchIncrement  = 0;

    /**
     * Create a new LumberjackAI
     *
     * @param job the lumberjackjob
     */
    public EntityAIWorkLumberjack(JobLumberjack job)
    {
        super(job);
        super.registerTargets(
                new AITarget(IDLE, () -> START_WORKING),
                new AITarget(START_WORKING, this::startWorkingAtOwnBuilding),
                new AITarget(PREPARING, this::prepareForWoodcutting),
                new AITarget(LUMBERJACK_SEARCHING_TREE, this::findTrees),
                new AITarget(LUMBERJACK_CHOP_TREE, this::chopWood),
                new AITarget(LUMBERJACK_GATHERING, this::gathering),
                new AITarget(LUMBERJACK_NO_TREES_FOUND, this::waitBeforeCheckingAgain)
                             );
    }

    /**
     * Walk to own building to check for tools.
     *
     * @return PREPARING once at the building
     */
    private AIState startWorkingAtOwnBuilding()
    {
        if (walkToBuilding())
        {
            return state;
        }
        return PREPARING;
    }

    /**
     * Checks if lumberjack has all necessary tools
     *
     * @return next AIState
     */
    private AIState prepareForWoodcutting()
    {
        if (checkForAxe())
        {
            return state;
        }
        return LUMBERJACK_SEARCHING_TREE;
    }

    /**
     * If the search radius was exceeded,
     * we have to wait dome time before
     * searching again.
     *
     * @return LUMBERJACK_SEARCHING_TREE once waited enough
     */
    private AIState waitBeforeCheckingAgain()
    {
        if (hasNotDelayed(WAIT_BEFORE_SEARCH))
        {
            return state;
        }
        return LUMBERJACK_SEARCHING_TREE;
    }

    /**
     * Checks if lumberjack has already found some trees. If not search trees.
     *
     * @return next AIState
     */
    private AIState findTrees()
    {
        if (job.tree == null)
        {
            return findTree();
        }
        return LUMBERJACK_CHOP_TREE;
    }

    /**
     * Search for a tree
     *
     * @return LUMBERJACK_GATHERING if job was canceled
     */
    private AIState findTree()
    {
        if (pathResult == null || pathResult.treeLocation == null)
        {
            pathResult = worker.getNavigator().moveToTree(SEARCH_RANGE + searchIncrement, 1.0D);
            return state;
        }
        if (pathResult.getPathReachesDestination())
        {
            if (pathResult.treeLocation != null)
            {
                job.tree = new Tree(world, pathResult.treeLocation);
                job.tree.findLogs(world);
            }
            else
            {
                setDelay(WAIT_BEFORE_INCREMENT);
                if (searchIncrement + SEARCH_RANGE > SEARCH_LIMIT)
                {
                    return LUMBERJACK_NO_TREES_FOUND;
                }
                searchIncrement += SEARCH_INCREMENT;
            }
            pathResult = null;

            return state;
        }
        if (pathResult.isCancelled())
        {
            pathResult = null;
            return LUMBERJACK_GATHERING;
        }
        return state;
    }

    /**
     * Again checks if all preconditions are given to execute chopping.
     * If yes go chopping, else return to previous AIStates.
     *
     * @return next AIState
     */
    private AIState chopWood()
    {
        if (checkForAxe())
        {
            return IDLE;
        }
        if (job.tree == null)
        {
            return LUMBERJACK_SEARCHING_TREE;
        }

        return chopTree();
    }

    /**
     * Work on the tree.
     * First find your way to the tree trunk.
     * Then chop away
     * and wait for saplings to drop
     * then place a sapling
     *
     * @return LUMBERJACK_GATHERING if tree is done
     */
    private AIState chopTree()
    {
        BlockPos location = job.tree.getLocation();
        if (walkToBlock(location))
        {
            checkIfStuckOnLeaves(location);
            return state;
        }

        if (!job.tree.hasLogs())
        {
            if (hasNotDelayed(WAIT_BEFORE_SAPLING))
            {
                return state;
            }
            plantSapling();
            return LUMBERJACK_GATHERING;
        }

        //take first log from queue
        BlockPos log = job.tree.peekNextLog();
        if (!mineBlock(log))
        {
            return state;
        }
        job.tree.pollNextLog();
        return state;
    }

    /**
     * Place a sappling for the current tree.
     */
    private void plantSapling()
    {
        //TODO place correct sapling
        plantSapling(job.tree.getLocation());
        job.tree = null;
    }

    /**
     * Plant a sapling at said location.
     * <p>
     * todo: make sure to get the right sapling
     *
     * @param location the location to plant the sapling at
     * @return true if a sapling was planted
     */
    private boolean plantSapling(BlockPos location)
    {
        if (BlockPosUtil.getBlock(world, location) != Blocks.air)
        {
            return false;
        }

        for (int slot = 0; slot < getInventory().getSizeInventory(); slot++)
        {
            ItemStack stack = getInventory().getStackInSlot(slot);
            if (isStackSapling(stack))
            {
                Block block = ((ItemBlock) stack.getItem()).getBlock();
                worker.setHeldItem(slot);
                if (BlockPosUtil.setBlock(world, location, block.getDefaultState(), 0x02))
                {
                    worker.swingItem();
                    world.playSoundEffect((float) location.getX() + 0.5F,
                                          (float) location.getY() + 0.5F,
                                          (float) location.getZ() + 0.5F,
                                          block.stepSound.getBreakSound(),
                                          block.stepSound.getVolume(),
                                          block.stepSound.getFrequency());
                    getInventory().decrStackSize(slot, 1);
                    setDelay(10);
                    return true;
                }
                break;
            }
        }
        return false;
    }

    /**
     * Checks if a stack is a type of sapling
     *
     * @param stack the stack to check
     * @return true if sapling
     */
    private boolean isStackSapling(ItemStack stack)
    {
        return stack != null && stack.getItem() instanceof ItemBlock && ((ItemBlock) stack.getItem()).getBlock() instanceof BlockSapling;
    }

    /**
     * Check if distance to block changed and
     * if we are not moving for too long, try to get unstuck
     *
     * @param location the block we want to go to
     */
    private void checkIfStuckOnLeaves(final BlockPos location)
    {
        int distance = (int) location.distanceSq(worker.getPosition());
        if (previousDistance != distance)
        {
            //something is moving, reset counters
            stillTicks = 0;
            previousDistance = distance;
            return;
        }
        //Stuck, probably on leaves
        stillTicks++;
        if (stillTicks < STUCK_WAIT_TIME)
        {
            //Wait for some time before jumping to conclusions
            return;
        }
        //now we seem to be stuck!
        tryGettingUnstuckFromLeaves();
    }

    /**
     * We are stuck, remove some leaves and try to get unstuck
     * <p>
     * if this takes too long, try backing up a bit
     */
    private void tryGettingUnstuckFromLeaves()
    {
        BlockPos nextLeaves = findNearLeaves();
        //If the worker gets too stuck he moves around a bit
        if (nextLeaves == null || stillTicks > WALKING_BACK_WAIT_TIME)
        {
            worker.getNavigator().moveAwayFromXYZ(worker.getPosition(), WALK_BACK_RANGE, WALK_BACK_SPEED);
            stillTicks = 0;
            return;
        }
        if (!mineBlock(nextLeaves))
        {
            return;
        }
        stillTicks = 0;

    }

    /**
     * Utility method to check for leaves around the citizen.
     * <p>
     * Will report the location of the first leaves block it finds.
     *
     * @return a leaves block or null if none found
     */
    private BlockPos findNearLeaves()
    {
        int playerX = (int) worker.getPosition().getX();
        int playerY = (int) (worker.getPosition().getY() + 1);
        int playerZ = (int) worker.getPosition().getZ();
        int radius  = 3;
        for (int x = playerX; x < playerX + radius; x++)
        {
            for (int y = playerY; y < playerY + radius; y++)
            {
                for (int z = playerZ; z < playerZ + radius; z++)
                {
                    BlockPos pos = new BlockPos(x,y,z);
                    if (world.getBlockState(pos).getBlock().isLeaves(world, pos))
                    {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Checks if the lumberjack found items on the ground,
     * if yes collect them, if not search for them.
     *
     * @return LUMBERJACK_GATHERING as long as gathering takes.
     */
    private AIState gathering()
    {
        if (items == null)
        {
            searchForItems();
        }
        if (!items.isEmpty())
        {
            gatherItems();
            return state;
        }
        items = null;
        return LUMBERJACK_SEARCHING_TREE;
    }

    /**
     * Search for all items around the Lumberjack
     * and store them in the items list
     */
    private void searchForItems()
    {
       items = new ArrayList<>();
        List<EntityItem> list = new ArrayList<>();
        for (Object o : world.getEntitiesWithinAABB(EntityItem.class, worker.getEntityBoundingBox().expand(RANGE_HORIZONTAL_PICKUP, RANGE_VERTICAL_PICKUP, RANGE_HORIZONTAL_PICKUP)))
        {
            if (o instanceof EntityItem)
            {
                list.add((EntityItem) o);
            }
        }

        //TODO check if sapling or apple (currently picks up all items, which may be okay)
        items = list.stream()
                    .filter(item -> item != null && !item.isDead)
                    .map(BlockPosUtil::fromEntity)
                    .collect(Collectors.toList());
    }

    /**
     * Collect one item by walking to it
     */
    private void gatherItems()
    {
        worker.setCanPickUpLoot(true);
        if (worker.getNavigator().noPath())
        {
            BlockPos pos = getAndRemoveClosestItem();
            worker.isWorkerAtSiteWithMove(pos, 3);
            return;
        }
        if (worker.getNavigator().getPath() == null)
        {
            setDelay(WAIT_WHILE_WALKING);
            return;
        }

        int currentIndex = worker.getNavigator().getPath().getCurrentPathIndex();
        //We moved a bit, not stuck
        if (currentIndex != previousIndex)
        {
            stillTicks = 0;
            previousIndex = currentIndex;
            return;
        }

        stillTicks++;
        //Stuck for too long
        if (stillTicks > 20)
        {
            //Skip this item
            worker.getNavigator().clearPathEntity();
        }
    }

    /**
     * Find the closest item and remove it from the list.
     *
     * @return the closest item
     */
    private BlockPos getAndRemoveClosestItem()
    {
        int   index    = 0;
        double distance = Double.MAX_VALUE;

        for (int i = 0; i < items.size(); i++)
        {
            double tempDistance = items.get(i).distanceSq(worker.getPosition());
            if (tempDistance < distance)
            {
                index = i;
                distance = tempDistance;
            }
        }

        return items.remove(index);
    }

    /**
     * Override this method if you want to keep some items in inventory.
     * When the inventory is full, everything get's dumped into the building chest.
     * But you can use this method to hold some stacks back.
     *
     * @param stack the stack to decide on
     * @return true if the stack should remain in inventory
     */
    @Override
    protected boolean neededForWorker(ItemStack stack)
    {
        return isStackAxe(stack) || isStackSapling(stack);
    }

    /**
     * Check if a stack is an axe
     * todo: use parent code
     *
     * @param stack the stack to check
     * @return true if an axe
     */
    private boolean isStackAxe(ItemStack stack)
    {
        return stack != null && stack.getItem().getToolClasses(stack).contains(TOOL_TYPE_AXE);
    }

    /**
     * Can be overridden in implementations.
     * <p>
     * Here the AI can check if the chestBelt has to be re rendered and do it.
     */
    @Override
    protected void updateRenderMetaData()
    {
        worker.setRenderMetadata(hasLogs() ? RENDER_META_LOGS : "");
    }

    /**
     * Checks if the lumberjack has logs in it's inventory.
     *
     * @return true if he has logs
     */
    private boolean hasLogs()
    {
        for (int i = 0; i < getInventory().getSizeInventory(); i++)
        {
            if (isStackLog(getInventory().getStackInSlot(i)))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a stack is a type of log
     *
     * @param stack the stack to check
     * @return true if it is a log type
     */
    private boolean isStackLog(ItemStack stack)
    {
        return stack != null && stack.getItem() instanceof ItemBlock && ((ItemBlock) stack.getItem()).getBlock().isWood(null,new BlockPos(0,0,0));
    }

    /**
     * This method will be overridden by AI implementations.
     * It will serve as a tick function.
     */
    @Override
    protected void workOnTask()
    {
        //Migration to new system complete
    }
}