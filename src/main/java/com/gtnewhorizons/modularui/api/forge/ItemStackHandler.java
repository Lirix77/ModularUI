package com.gtnewhorizons.modularui.api.forge;
//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

import java.util.Arrays;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

public class ItemStackHandler implements IItemHandlerModifiable, INBTSerializable<NBTTagCompound> {

    protected List<ItemStack> stacks;

    public ItemStackHandler() {
        this(1);
    }

    public ItemStackHandler(int size) {
        ItemStack[] stacks = new ItemStack[size];
        Arrays.fill(stacks, null);
        this.stacks = Arrays.asList(stacks);
    }

    public ItemStackHandler(List<ItemStack> stacks) {
        this.stacks = stacks;
    }

    public ItemStackHandler(ItemStack[] stacks) {
        this.stacks = Arrays.asList(stacks);
    }

    public void setSize(int size) {
        ItemStack[] stacks = new ItemStack[size];
        Arrays.fill(stacks, null);
        this.stacks = Arrays.asList(stacks);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        this.validateSlotIndex(slot);
        this.stacks.set(slot, stack);
        this.onContentsChanged(slot);
    }

    @Override
    public int getSlots() {
        return this.stacks.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        this.validateSlotIndex(slot);
        return (ItemStack) this.stacks.get(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack == null) {
            return null;
        } else {
            this.validateSlotIndex(slot);
            ItemStack existing = (ItemStack) this.stacks.get(slot);
            int limit = this.getStackLimit(slot, stack);
            if (existing != null) {
                if (!ItemHandlerHelper.canItemStacksStack(stack, existing)) {
                    return stack;
                }

                limit -= existing.stackSize;
            }

            if (limit <= 0) {
                return stack;
            } else {
                boolean reachedLimit = stack.stackSize > limit;
                if (!simulate) {
                    if (existing == null) {
                        this.stacks.set(slot, reachedLimit ? ItemHandlerHelper.copyStackWithSize(stack, limit) : stack);
                    } else {
                        existing.stackSize += reachedLimit ? limit : stack.stackSize;
                    }

                    this.onContentsChanged(slot);
                }

                return reachedLimit ? ItemHandlerHelper.copyStackWithSize(stack, stack.stackSize - limit) : null;
            }
        }
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount == 0) {
            return null;
        } else {
            this.validateSlotIndex(slot);
            ItemStack existing = (ItemStack) this.stacks.get(slot);
            if (existing == null) {
                return null;
            } else {
                int toExtract = Math.min(amount, existing.getMaxStackSize());
                if (existing.stackSize <= toExtract) {
                    if (!simulate) {
                        this.stacks.set(slot, null);
                        this.onContentsChanged(slot);
                    }

                    return existing;
                } else {
                    if (!simulate) {
                        this.stacks.set(
                                slot,
                                ItemHandlerHelper.copyStackWithSize(existing, existing.stackSize - toExtract));
                        this.onContentsChanged(slot);
                    }

                    return ItemHandlerHelper.copyStackWithSize(existing, toExtract);
                }
            }
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }

    protected int getStackLimit(int slot, ItemStack stack) {
        return Math.min(this.getSlotLimit(slot), stack.getMaxStackSize());
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return true;
    }

    @Override
    public NBTTagCompound serializeNBT() {
        NBTTagList nbtTagList = new NBTTagList();

        for (int i = 0; i < this.stacks.size(); ++i) {
            if (this.stacks.get(i) != null) {
                NBTTagCompound itemTag = new NBTTagCompound();
                itemTag.setInteger("Slot", i);
                this.stacks.get(i).writeToNBT(itemTag);
                itemTag.setInteger("Count", stacks.get(i).stackSize);
                nbtTagList.appendTag(itemTag);
            }
        }

        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setTag("Items", nbtTagList);
        nbt.setInteger("Size", this.stacks.size());
        return nbt;
    }

    @Override
    public void deserializeNBT(NBTTagCompound nbt) {
        this.setSize(nbt.hasKey("Size", 3) ? nbt.getInteger("Size") : this.stacks.size());
        NBTTagList tagList = nbt.getTagList("Items", 10);

        for (int i = 0; i < tagList.tagCount(); ++i) {
            NBTTagCompound itemTags = tagList.getCompoundTagAt(i);
            int slot = itemTags.getInteger("Slot");
            if (slot >= 0 && slot < this.stacks.size()) {
                ItemStack loadedStack = ItemStack.loadItemStackFromNBT(itemTags);
                if (loadedStack != null && itemTags.hasKey("Count", Constants.NBT.TAG_INT)) {
                    loadedStack.stackSize = itemTags.getInteger("Count");
                }
                this.stacks.set(slot, loadedStack);
            }
        }

        this.onLoad();
    }

    protected void validateSlotIndex(int slot) {
        if (slot < 0 || slot >= this.stacks.size()) {
            throw new RuntimeException("Slot " + slot + " not in valid range - [0," + this.stacks.size() + ")");
        }
    }

    protected void onLoad() {}

    protected void onContentsChanged(int slot) {}
}
