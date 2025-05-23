package com.gtnewhorizons.modularui.api.widget;

import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;
import com.google.gson.JsonObject;
import com.gtnewhorizons.modularui.ModularUI;
import com.gtnewhorizons.modularui.api.GlStateManager;
import com.gtnewhorizons.modularui.api.drawable.IDrawable;
import com.gtnewhorizons.modularui.api.drawable.Text;
import com.gtnewhorizons.modularui.api.math.Color;
import com.gtnewhorizons.modularui.api.math.Pos2d;
import com.gtnewhorizons.modularui.api.math.Size;
import com.gtnewhorizons.modularui.api.screen.ModularUIContext;
import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.common.internal.JsonHelper;
import com.gtnewhorizons.modularui.common.internal.Theme;
import com.gtnewhorizons.modularui.common.widget.FakeSyncWidget;
import cpw.mods.fml.common.Optional;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.util.GTTooltipDataCache;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.StatCollector;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * This class draws a functional element of ModularUI
 */
public abstract class Widget {

    // gui
    private String name = "";
    private ModularWindow window = null;
    private IWidgetParent parent = null;

    // sizing and positioning
    protected Size size = Size.ZERO;
    protected Pos2d relativePos = Pos2d.ZERO;
    protected Pos2d pos = Pos2d.ZERO;
    protected Pos2d fixedPos = null;

    @Nullable
    private SizeProvider sizeProvider;

    @Nullable
    private PosProvider posProvider;

    private boolean fillParent = false;
    private boolean autoSized = true;
    private boolean autoPositioned = true;

    // flags and stuff
    private Function<Widget, Boolean> enabledDynamic = widget -> true;
    private boolean enabledStatic = true;
    private int layer = -1;
    private boolean tooltipDirty = true;
    private boolean firstRebuild = true;
    private Supplier<String> internalName = () -> null;

    // visuals
    @NotNull
    private Supplier<IDrawable[]> background = () -> null;

    private final List<Text> additionalTooltip = new ArrayList<>();
    private final List<Text> mainTooltip = new ArrayList<>();
    private Supplier<List<String>> dynamicTooltip;
    private final List<Text> additionalTooltipShift = new ArrayList<>();
    private final List<Text> mainTooltipShift = new ArrayList<>();
    private Supplier<List<String>> dynamicTooltipShift;
    private int tooltipShowUpDelay = 0;
    private boolean updateTooltipEveryTick = false;
    private boolean tooltipHasSpaceAfterFirstLine = true;

    @Nullable
    private String debugLabel;

    @Nullable
    private Consumer<Widget> ticker;

    // NEI
    private boolean respectNEIArea = true;
    private boolean hasTransferRect;
    private String transferRectID;
    private Object[] transferRectArgs;
    private String transferRectTooltip;

    public Widget() {}

    public Widget(Size size) {
        this();
        setSize(size);
    }

    public Widget(Pos2d pos) {
        this();
        setPos(pos);
    }

    public Widget(Size size, Pos2d pos) {
        this();
        setSize(size);
        setPos(pos);
    }

    /**
     * @return if we are on logical client or server
     */
    public boolean isClient() {
        return getContext().isClient();
    }

    /**
     * Called when this widget is created from json. Make sure to call super.readJson(json, type);
     *
     * @param json the widget json
     * @param type the type this widget was created with
     */
    public void readJson(JsonObject json, String type) {
        this.name = JsonHelper.getString(json, "", "name");
        this.relativePos = JsonHelper.getElement(json, relativePos, Pos2d::ofJson, "pos");
        this.fixedPos = JsonHelper.getElement(json, null, Pos2d::ofJson, "fixedPos");
        this.size = JsonHelper.getElement(json, size, Size::ofJson, "size");
        this.fillParent = JsonHelper.getBoolean(json, false, "fillParent");
        setEnabled(JsonHelper.getBoolean(json, true, "enabled"));
        this.autoSized = JsonHelper.getBoolean(json, !json.has("size"), "autoSized");
        IDrawable drawable = JsonHelper.getObject(json, null, IDrawable::ofJson, "drawable", "background");
        if (drawable != null) {
            setBackground(drawable);
        }
    }

    // ==== Internal methods ====

    /**
     * You shall not call this
     */
    @ApiStatus.Internal
    public final void initialize(ModularWindow window, IWidgetParent parent, int layer) {
        if (window == null || parent == null || isInitialised()) {
            throw new IllegalStateException("Illegal initialise call to widget!! " + toString());
        }
        this.window = window;
        this.parent = parent;
        this.layer = layer;

        onInit();

        if (this instanceof IWidgetParent) {
            int nextLayer = layer + 1;
            for (Widget widget : ((IWidgetParent) this).getChildren()) {
                widget.initialize(this.window, (IWidgetParent) this, nextLayer);
            }
        }

        onPostInit();
    }

    @SideOnly(Side.CLIENT)
    @ApiStatus.Internal
    public final void buildTopToBottom(Dimension constraints) {
        if (!isInitialised()) {
            return;
        }
        int cw = constraints.width, ch = constraints.height;
        if (this instanceof IWidgetParent) {
            modifyConstraints(constraints);
            IWidgetParent parentThis = (IWidgetParent) this;
            for (Widget widget : parentThis.getChildren()) {
                widget.buildTopToBottom(constraints);
            }
            parentThis.layoutChildren(cw, ch);
        }
        if (isAutoSized() && !isFillParent()) {
            this.size = determineSize(cw, ch);
        }
    }

    /**
     * You shall not call this
     */
    @SideOnly(Side.CLIENT)
    @ApiStatus.Internal
    public final void buildBottomToTop() {
        if (!isInitialised()) {
            return;
        }
        if (isAutoSized() && isFillParent()) {
            this.size = parent.getSize();
        } else if (this.sizeProvider != null) {
            this.size = this.sizeProvider.getSize(getContext().getScaledScreenSize(), getWindow(), this.parent);
        }
        // calculate positions
        if (isFixed() && !isAutoPositioned()) {
            relativePos = fixedPos.subtract(parent.getAbsolutePos());
            pos = fixedPos;
        } else {
            if (this.posProvider != null) {
                this.relativePos = this.posProvider
                        .getPos(getContext().getScaledScreenSize(), getWindow(), this.parent);
            }
            this.pos = this.parent.getAbsolutePos().add(this.relativePos);
        }

        if (this instanceof IWidgetParent) {
            IWidgetParent parentThis = (IWidgetParent) this;
            // rebuild children
            for (Widget child : parentThis.getChildren()) {
                child.buildBottomToTop();
            }
        }
        if (firstRebuild) {
            onFirstRebuild();
            firstRebuild = false;
        }
        onRebuild();
    }

    @SideOnly(Side.CLIENT)
    @ApiStatus.Internal
    public final void drawInternal(float partialTicks) {
        drawInternal(partialTicks, false);
    }

    /**
     * You shall not call this
     */
    @SideOnly(Side.CLIENT)
    @ApiStatus.Internal
    public final void drawInternal(float partialTicks, boolean ignoreEnabled) {
        onFrameUpdate();
        if (isEnabled() || ignoreEnabled) {
            GlStateManager.pushMatrix();
            Pos2d windowPos = getWindow().getPos();
            Size windowSize = getWindow().getSize();
            int alpha = getWindow().getAlpha();
            float scale = getWindow().getScale();
            float sf = 1 / scale;
            // translate to center according to scale
            float x = (windowPos.x + windowSize.width / 2f * (1 - scale) + (pos.x - windowPos.x) * scale) * sf;
            float y = (windowPos.y + windowSize.height / 2f * (1 - scale) + (pos.y - windowPos.y) * scale) * sf;
            GlStateManager.translate(x, y, 0);
            IDrawable.applyTintColor(getWindow().getGuiTint());
            GlStateManager.enableBlend();
            drawBackground(partialTicks);
            draw(partialTicks);
            GlStateManager.popMatrix();

            if (this instanceof IWidgetParent) {
                ((IWidgetParent) this).drawChildren(partialTicks);
            }
        }
    }

    // ==== Sizing & Positioning ====

    /**
     * Called before this widget ask for the children Size.
     *
     * @param constraints constraints to modify
     */
    @SideOnly(Side.CLIENT)
    protected void modifyConstraints(Dimension constraints) {}

    /**
     * Called during rebuild
     *
     * @param maxWidth  maximum width to fit in parent
     * @param maxHeight maximum height to fit in parent
     * @return the preferred size
     */
    @SideOnly(Side.CLIENT)
    protected @NotNull Size determineSize(int maxWidth, int maxHeight) {
        return new Size(maxWidth, maxHeight);
    }

    /**
     * Called after this widget is rebuild aka size and pos are set.
     */
    @ApiStatus.OverrideOnly
    @SideOnly(Side.CLIENT)
    public void onRebuild() {}

    /**
     * Called the first time this widget is fully build
     */
    @ApiStatus.OverrideOnly
    @SideOnly(Side.CLIENT)
    public void onFirstRebuild() {}

    /**
     * Causes the UI to re-layout all children next screen update
     */
    public void checkNeedsRebuild() {
        if (isInitialised() && isClient()) {
            window.markNeedsRebuild();
        }
    }

    // ==== Update ====

    /**
     * Called once per tick
     */
    @ApiStatus.OverrideOnly
    @SideOnly(Side.CLIENT)
    public void onScreenUpdate() {}

    /**
     * Called each frame, approximately 60 times per second
     */
    @ApiStatus.OverrideOnly
    @SideOnly(Side.CLIENT)
    public void onFrameUpdate() {}

    // ==== Rendering ====

    @SideOnly(Side.CLIENT)
    public void drawBackground(float partialTicks) {
        IDrawable[] background = getBackground();
        if (background != null) {
            int themeColor = Theme.INSTANCE.getColor(getBackgroundColorKey());
            for (IDrawable drawable : background) {
                if (drawable != null) {
                    drawable.applyThemeColor(themeColor);
                    drawable.draw(Pos2d.ZERO, getSize(), partialTicks);
                }
            }
        }
    }

    /**
     * Draw the widget here
     *
     * @param partialTicks ticks since last draw
     */
    @ApiStatus.OverrideOnly
    @SideOnly(Side.CLIENT)
    public void draw(float partialTicks) {}

    /**
     * Is called after all widgets of the window are drawn. Can be used for special tooltip rendering.
     *
     * @param partialTicks ticks since last draw
     */
    @ApiStatus.OverrideOnly
    @SideOnly(Side.CLIENT)
    public void drawInForeground(float partialTicks) {}

    /**
     * Called after {@link #notifyTooltipChange()} is called. Result list is cached
     *
     * @param tooltip tooltip
     */
    @ApiStatus.OverrideOnly
    @SideOnly(Side.CLIENT)
    public void buildTooltip(List<Text> tooltip) {
        if (dynamicTooltip != null) {
            tooltip.addAll(
                    dynamicTooltip.get().stream().map(s -> new Text(s).color(Color.WHITE.normal))
                            .collect(Collectors.toList()));
        }
    }

    /**
     * Called after {@link #notifyTooltipChange()} is called. Result list is cached
     *
     * @param tooltipShift tooltip shown while holding shift key
     */
    @ApiStatus.OverrideOnly
    @SideOnly(Side.CLIENT)
    public void buildTooltipShift(List<Text> tooltipShift) {
        if (dynamicTooltipShift != null) {
            tooltipShift.addAll(
                    dynamicTooltipShift.get().stream().map(s -> new Text(s).color(Color.WHITE.normal))
                            .collect(Collectors.toList()));
        }
    }

    /**
     * @return the color key for the background
     * @see Theme
     */
    @SideOnly(Side.CLIENT)
    @Nullable
    public String getBackgroundColorKey() {
        return Theme.KEY_BACKGROUND;
    }

    // ==== Lifecycle ====

    /**
     * Called once when the window opens, before children get initialised.
     */
    @ApiStatus.OverrideOnly
    public void onInit() {}

    /**
     * Called once when the window opens, after children get initialised.
     */
    @ApiStatus.OverrideOnly
    public void onPostInit() {}

    /**
     * Called when another window opens over the current one or when this window is active and it closes
     */
    @ApiStatus.OverrideOnly
    public void onPause() {}

    /**
     * Called when this window becomes active after being paused
     */
    @ApiStatus.OverrideOnly
    public void onResume() {}

    /**
     * Called when this window closes
     */
    @ApiStatus.OverrideOnly
    public void onDestroy() {}

    // ==== focus ====

    /**
     * Called when this widget is clicked. Also acts as a onReceiveFocus method.
     *
     * @return if the ui focus should be set to this widget
     */
    @ApiStatus.OverrideOnly
    @SideOnly(Side.CLIENT)
    public boolean shouldGetFocus() {
        return this instanceof Interactable;
    }

    /**
     * Called when this widget was focused and now something else is focused
     */
    @ApiStatus.OverrideOnly
    @SideOnly(Side.CLIENT)
    public void onRemoveFocus() {}

    /**
     * @return If current UI has this widget focused
     */
    @SideOnly(Side.CLIENT)
    public boolean isFocused() {
        return getContext().getCursor().isFocused(this);
    }

    /**
     * Removes the focus from this widget. Does nothing if it isn't focused
     */
    @SideOnly(Side.CLIENT)
    public void removeFocus() {
        getContext().getCursor().removeFocus(this);
    }

    @SideOnly(Side.CLIENT)
    public boolean canHover() {
        return this instanceof Interactable || hasTooltip();
    }

    /**
     * @return if this is currently the top most widget under the mouse
     */
    @SideOnly(Side.CLIENT)
    public boolean isHovering() {
        return getContext().getCursor().isHovering(this);
    }

    @SideOnly(Side.CLIENT)
    public boolean isRightBelowMouse() {
        return getContext().getCursor().isRightBelow(this);
    }

    // ==== Debug ====

    @Override
    public String toString() {
        if (debugLabel == null && name.isEmpty()) {
            return getClass().getSimpleName();
        }
        if (debugLabel == null) {
            return getClass().getSimpleName() + "#" + name;
        }
        return getClass().getSimpleName() + "#" + name + "#" + debugLabel;
    }

    // ==== Getter ====

    public boolean isUnderMouse(Pos2d mousePos) {
        return mousePos.isInside(pos, size);
    }

    public String getName() {
        return name;
    }

    public ModularUIContext getContext() {
        return window.getContext();
    }

    public ModularWindow getWindow() {
        return window;
    }

    public String getInternalName() {
        return internalName.get();
    }

    /**
     * Which window does this widget belong to. 0 == main window.
     */
    public int getWindowLayer() {
        int i = 0;
        for (ModularWindow window : getContext().getOpenWindowsReversed()) {
            if (window == getWindow()) return i;
            i++;
        }
        return 0;
    }

    public IWidgetParent getParent() {
        return parent;
    }

    @SideOnly(Side.CLIENT)
    public Rectangle getArea() {
        return new Rectangle(pos.x, pos.y, size.width, size.height);
    }

    public Pos2d getPos() {
        return relativePos;
    }

    public Pos2d getAbsolutePos() {
        return pos;
    }

    public Size getSize() {
        return size;
    }

    /**
     * @return Rectangle used to check if this widget overlaps with NEI elements
     */
    public Rectangle getRenderAbsoluteRectangle() {
        return new Rectangle(getAbsolutePos().x, getAbsolutePos().y, getSize().width, getSize().height);
    }

    public boolean isEnabled() {
        return enabledStatic && enabledDynamic.apply(this);
    }

    public int getLayer() {
        return layer;
    }

    public final boolean isInitialised() {
        return window != null;
    }

    public boolean isFixed() {
        return fixedPos != null;
    }

    public boolean isAutoSized() {
        return autoSized;
    }

    public boolean isAutoPositioned() {
        return autoPositioned;
    }

    public boolean isFillParent() {
        return fillParent;
    }

    @Nullable
    public String getDebugLabel() {
        return debugLabel;
    }

    @Nullable
    public IDrawable[] getBackground() {
        return background.get();
    }

    public boolean hasNEITransferRect() {
        return hasTransferRect;
    }

    @Nullable
    public String getNEITransferRectID() {
        return transferRectID;
    }

    @Nullable
    public Object[] getNEITransferRectArgs() {
        return transferRectArgs;
    }

    @Nullable
    public String getNEITransferRectTooltip() {
        return transferRectTooltip;
    }

    public void handleTransferRectMouseClick(boolean usage) {
        String id = getNEITransferRectID();
        Object[] args = getNEITransferRectArgs();
        Interactable.playButtonClickSound();
        if (usage) {
            GuiUsageRecipe.openRecipeGui(id);
        } else {
            GuiCraftingRecipe.openRecipeGui(id, args);
        }
    }

    private void checkTooltip() {
        if (this.tooltipDirty || this.updateTooltipEveryTick) {
            this.mainTooltip.clear();
            this.mainTooltipShift.clear();
            buildTooltip(this.mainTooltip);
            buildTooltipShift(this.mainTooltipShift);
            this.tooltipDirty = false;
        }
    }

    public void notifyTooltipChange() {
        this.tooltipDirty = true;
    }

    public boolean hasTooltip() {
        checkTooltip();
        return !this.mainTooltip.isEmpty() || !this.additionalTooltip.isEmpty()
                || !this.mainTooltipShift.isEmpty()
                || !this.additionalTooltipShift.isEmpty()
                || hasNEITransferRect();
    }

    public List<Text> getTooltip() {
        if (!hasTooltip()) {
            return Collections.emptyList();
        }
        List<Text> tooltip;
        if (Interactable.hasShiftDown()) {
            if (this.mainTooltipShift.isEmpty()) {
                tooltip = new ArrayList<>(this.mainTooltip);
            } else {
                tooltip = new ArrayList<>(this.mainTooltipShift);
            }
            if (this.additionalTooltipShift.isEmpty()) {
                tooltip.addAll(this.additionalTooltip);
            } else {
                tooltip.addAll(this.additionalTooltipShift);
            }
        } else {
            tooltip = new ArrayList<>(this.mainTooltip);
            tooltip.addAll(this.additionalTooltip);
        }
        return tooltip;
    }

    public int getTooltipShowUpDelay() {
        return tooltipShowUpDelay;
    }

    @Nullable
    public Consumer<Widget> getTicker() {
        return ticker;
    }

    public boolean intersects(Widget widget) {
        return !(widget.getAbsolutePos().x > getAbsolutePos().x + getSize().width
                || widget.getAbsolutePos().x + widget.getSize().width < getAbsolutePos().x
                || widget.getAbsolutePos().y > getAbsolutePos().y + getSize().height
                || widget.getAbsolutePos().y + widget.getSize().height < getAbsolutePos().y);
    }

    public Rectangle getRectangle() {
        return new Rectangle(pos.x, pos.y, size.width, size.height);
    }

    public boolean isRespectNEIArea() {
        return respectNEIArea;
    }

    // ==== Setter/Builder ====

    /**
     * If widgets are NOT enabled, they won't be rendered and cannot be interacted with. When used together with
     * {@link #setEnabled(Function)}, this widget is recognized to be enabled only when both requirements are met.
     *
     * @param enabled if this widget should be enabled
     */
    public Widget setEnabled(boolean enabled) {
        this.enabledStatic = enabled;
        return this;
    }

    /**
     * Changes enabled state dynamically. Note that function is called on every render tick.
     */
    public Widget setEnabled(Function<Widget, Boolean> enabled) {
        this.enabledDynamic = enabled;
        return this;
    }

    /**
     * {@link #setEnabled(boolean)} and {@link #setEnabled(Function)} works independently. This method overwrites both
     * of behaviors.
     */
    public Widget setEnabledForce(boolean enabled) {
        setEnabled(enabled);
        setEnabled(widget -> enabled);
        return this;
    }

    public Widget setSize(int width, int height) {
        return setSize(new Size(width, height));
    }

    /**
     * Forces the widget to a size
     *
     * @param size size of this widget
     */
    public Widget setSize(Size size) {
        checkNeedsRebuild();
        this.autoSized = false;
        this.fillParent = false;
        this.size = size;
        return this;
    }

    public Widget setSizeProvider(SizeProvider sizeProvider) {
        this.autoSized = false;
        this.fillParent = false;
        this.sizeProvider = sizeProvider;
        return this;
    }

    public void setSizeSilent(Size size) {
        this.size = size;
    }

    public Widget setPos(int x, int y) {
        return setPos(new Pos2d(x, y));
    }

    /**
     * Sets this widget to a pos relative to the parents pos
     *
     * @param relativePos relative pos
     */
    public Widget setPos(Pos2d relativePos) {
        checkNeedsRebuild();
        this.autoPositioned = false;
        this.relativePos = relativePos;
        this.fixedPos = null;
        return this;
    }

    public void setPosSilent(Pos2d relativePos) {
        this.relativePos = relativePos;
        if (isInitialised()) {
            this.pos = parent.getAbsolutePos().add(this.relativePos);
            if (this instanceof IWidgetParent) {
                for (Widget child : ((IWidgetParent) this).getChildren()) {
                    child.setPosSilent(child.getPos());
                }
            }
        }
    }

    public Widget setFixedPos(int x, int y) {
        return setFixedPos(new Pos2d(x, y));
    }

    /**
     * Sets the widgets pos to a fixed point. It will never move
     *
     * @param pos pos to fix this widget to
     */
    public Widget setFixedPos(@Nullable Pos2d pos) {
        checkNeedsRebuild();
        this.autoPositioned = false;
        this.fixedPos = pos;
        return this;
    }

    public Widget setPosProvider(PosProvider posProvider) {
        this.autoPositioned = false;
        this.posProvider = posProvider;
        this.fixedPos = null;
        return this;
    }

    /**
     * Sets the widgets size to its parent size
     */
    public Widget fillParent() {
        this.fillParent = true;
        this.autoSized = true;
        this.autoPositioned = false;
        return this;
    }

    /**
     * Sets a static background drawable.
     *
     * @param drawables background to render
     */
    public Widget setBackground(IDrawable... drawables) {
        this.background = () -> drawables;
        return this;
    }

    public Widget setBackground(Supplier<IDrawable[]> drawables) {
        this.background = drawables;
        return this;
    }

    /**
     * Adds a line to the tooltip
     */
    public Widget addTooltip(Text tooltip) {
        this.additionalTooltip.add(tooltip);
        return this;
    }

    /**
     * Adds a line to the tooltip
     */
    public Widget addTooltip(String tooltip) {
        return addTooltip(new Text(tooltip).color(Color.WHITE.normal));
    }

    public Widget addTooltips(List<String> tooltips) {
        for (String tooltip : tooltips) {
            addTooltip(tooltip);
        }
        return this;
    }

    /**
     * Sets getter for dynamic tooltip that will be called on {@link #buildTooltip}. Result is cached, so you also need
     * to call {@link #notifyTooltipChange} for update.
     */
    public Widget dynamicTooltip(Supplier<List<String>> dynamicTooltip) {
        this.dynamicTooltip = dynamicTooltip;
        return this;
    }

    /**
     * Adds a line to the tooltip shown while holding shift key
     */
    public Widget addTooltipShift(Text tooltipShift) {
        this.additionalTooltipShift.add(tooltipShift);
        return this;
    }

    /**
     * Adds a line to the tooltip shown while holding shift key
     */
    public Widget addTooltipShift(String tooltipShift) {
        return addTooltipShift(new Text(tooltipShift).color(Color.WHITE.normal));
    }

    public Widget addTooltipsShift(List<String> tooltipsShift) {
        for (String tooltip : tooltipsShift) {
            addTooltipShift(tooltip);
        }
        return this;
    }

    /**
     * Sets getter for dynamic tooltip that will be shown while holding shift key and called on {@link #buildTooltip}.
     * Result is cached, so you also need to call {@link #notifyTooltipChange} for update.
     */
    public Widget dynamicTooltipShift(Supplier<List<String>> dynamicTooltipShift) {
        this.dynamicTooltipShift = dynamicTooltipShift;
        return this;
    }

    @Optional.Method(modid = ModularUI.MODID_GT5U)
    public Widget setGTTooltip(Supplier<GTTooltipDataCache.TooltipData> tooltipDataGetter) {
        dynamicTooltip(() -> tooltipDataGetter.get().text);
        dynamicTooltipShift(() -> tooltipDataGetter.get().shiftText);
        return this;
    }

    public Widget setTooltipShowUpDelay(int tooltipShowUpDelay) {
        this.tooltipShowUpDelay = tooltipShowUpDelay;
        return this;
    }

    /**
     * By default, dynamic tooltip doesn't get updated unless {@link #notifyTooltipChange} is called. Passing true to
     * this method will force tooltip to update on every render tick.
     */
    public Widget setUpdateTooltipEveryTick(boolean updateTooltipEveryTick) {
        this.updateTooltipEveryTick = updateTooltipEveryTick;
        return this;
    }

    public Widget setTooltipHasSpaceAfterFirstLine(boolean tooltipHasSpaceAfterFirstLine) {
        this.tooltipHasSpaceAfterFirstLine = tooltipHasSpaceAfterFirstLine;
        return this;
    }

    public boolean isTooltipHasSpaceAfterFirstLine() {
        return tooltipHasSpaceAfterFirstLine;
    }

    public Widget setDebugLabel(String debugLabel) {
        this.debugLabel = debugLabel;
        return this;
    }

    /**
     * Applies this action each tick on client. Can be used to dynamically enable/disable the widget
     *
     * @param ticker tick function
     */
    public Widget setTicker(@Nullable Consumer<Widget> ticker) {
        this.ticker = ticker;
        return this;
    }

    /**
     * Consumes the widget. Can be used to apply advanced actions in a builder.
     *
     * @param widgetConsumer action to apply
     */
    public Widget consume(Consumer<Widget> widgetConsumer) {
        widgetConsumer.accept(this);
        return this;
    }

    /**
     * Effect is the same as when you store this widget in a variable, set
     * {@link FakeSyncWidget#setOnClientUpdate(Consumer)}, and add syncer to builder.
     *
     * @param syncer  To attach to this widget
     * @param builder To add syncer for window
     * @param onSync  Called when syncer receives packet from server
     */
    public <T> Widget attachSyncer(FakeSyncWidget<T> syncer, IWidgetBuilder<?> builder, BiConsumer<Widget, T> onSync) {
        builder.widget(syncer.setOnClientUpdate(val -> onSync.accept(this, val)));
        return this;
    }

    public <T> Widget attachSyncer(FakeSyncWidget<T> syncer, IWidgetBuilder<?> builder) {
        return attachSyncer(syncer, builder, (widget, val) -> {});
    }

    /**
     * Makes NEI respect this widget's area or not. This is used when the widget is outside its windows area and
     * overlaps with NEI. This is enabled by default.
     */
    public Widget setRespectNEIArea(boolean doRespect) {
        this.respectNEIArea = doRespect;
        return this;
    }

    public Widget setNEITransferRect(String transferRectID, Object[] transferRectArgs, String transferRectTooltip) {
        this.transferRectID = transferRectID;
        this.transferRectArgs = transferRectArgs;
        this.transferRectTooltip = transferRectTooltip;
        this.hasTransferRect = true;
        return this;
    }

    public Widget setNEITransferRect(String transferRectID, Object[] transferRectArgs) {
        return setNEITransferRect(
                transferRectID,
                transferRectArgs,
                StatCollector.translateToLocal("nei.recipe.tooltip"));
    }

    public Widget setNEITransferRect(String transferRectID, String transferRectTooltip) {
        return setNEITransferRect(transferRectID, new Object[0], transferRectTooltip);
    }

    public Widget setNEITransferRect(String transferRectID) {
        return setNEITransferRect(transferRectID, new Object[0]);
    }

    public Widget setInternalName(String internalName) {
        return setInternalName(() -> internalName);
    }

    public Widget setInternalName(Supplier<String> supplier) {
        internalName = supplier;
        return this;
    }

    // ==== Utility ====

    public interface SizeProvider {

        Size getSize(Size screenSize, ModularWindow window, IWidgetParent parent);
    }

    public interface PosProvider {

        Pos2d getPos(Size screenSize, ModularWindow window, IWidgetParent parent);
    }

    public static class ClickData {

        public final int mouseButton;
        public final boolean doubleClick;
        public final boolean shift;
        public final boolean ctrl;
        // public final boolean alt;

        public ClickData(int mouseButton, boolean doubleClick, boolean shift, boolean ctrl) {
            this.mouseButton = mouseButton;
            this.doubleClick = doubleClick;
            this.shift = shift;
            this.ctrl = ctrl;
            // this.alt = alt;
        }

        public void writeToPacket(PacketBuffer buffer) {
            short data = (short) (mouseButton & 0xFF);
            if (doubleClick) data |= 1 << 8;
            if (shift) data |= 1 << 9;
            if (ctrl) data |= 1 << 10;
            buffer.writeShort(data);
        }

        public static ClickData readPacket(PacketBuffer buffer) {
            short data = buffer.readShort();
            return new ClickData(data & 0xFF, (data & 1 << 8) > 0, (data & 1 << 9) > 0, (data & 1 << 10) > 0);
        }

        @SideOnly(Side.CLIENT)
        public static ClickData create(int mouse, boolean doubleClick) {
            return new ClickData(mouse, doubleClick, Interactable.hasShiftDown(), Interactable.hasControlDown());
        }
    }
}
