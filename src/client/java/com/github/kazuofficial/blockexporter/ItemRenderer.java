package com.github.kazuofficial.blockexporter;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ItemRenderer implements AutoCloseable {
	public static final Identifier ITEM_TEXTURE = Identifier.parse("blockexporter:items");
	public static final Identifier BLOCK_TEXTURE = Identifier.parse("blockexporter:blocks");

	private final int textureSize;
    private final Path exportDirectory;
    private final Minecraft client;
    private final TextureTarget framebuffer;
    private final Projection projection;
    private final ProjectionMatrixBuffer projectionMatrixBuffer;
    private final ItemStackRenderState itemRenderState;
    private final ExecutorService fileWriteExecutor;
    private final Semaphore fileWriteSemaphore;
    private final ConcurrentLinkedQueue<ItemStack> failedExports;

    private final PoseStack matrices;

    public ItemRenderer(int textureSize) {
        this.textureSize = textureSize;
        this.client = Minecraft.getInstance();
        this.exportDirectory = client.gameDirectory.toPath().resolve("item_exports");
		this.projection = new Projection();
		this.projection.setupOrtho(-1000, 1000, this.textureSize, this.textureSize, true);
        this.projectionMatrixBuffer = new ProjectionMatrixBuffer("item-exporter");
        this.itemRenderState = new ItemStackRenderState();
        int coreCount = Runtime.getRuntime().availableProcessors();
        this.fileWriteExecutor = Executors.newFixedThreadPool(Math.max(2, coreCount / 2));
        this.fileWriteSemaphore = new Semaphore(coreCount);
        this.failedExports = new ConcurrentLinkedQueue<>();

        this.matrices = new PoseStack();

        try {
            Files.createDirectories(exportDirectory);
            BlockExporter.LOGGER.info("Created export directory: {}", exportDirectory.toAbsolutePath());
        } catch (IOException e) {
            BlockExporter.LOGGER.error("Failed to create export directory: {}", exportDirectory.toAbsolutePath(), e);
            throw new RuntimeException("Failed to create export directory", e);
        }

        this.framebuffer = new TextureTarget("item-exporter", this.textureSize, this.textureSize, true);
    }

	public CompletableFuture<List<NativeImage>> exportAllBatch(List<Item> items) {
		if (items == null || items.isEmpty()) {
			return null;
		}

		CompletableFuture<List<NativeImage>> future = new CompletableFuture<>();

		var oldColor = RenderSystem.outputColorTextureOverride;
		var oldDepth = RenderSystem.outputDepthTextureOverride;

		try {
			RenderSystem.outputColorTextureOverride = this.framebuffer.getColorTextureView();
			RenderSystem.outputDepthTextureOverride = this.framebuffer.getDepthTextureView();
			RenderSystem.setProjectionMatrix(this.projectionMatrixBuffer.getBuffer(this.projection), ProjectionType.ORTHOGRAPHIC);

			future = itemsToImage(items);

		} catch (Exception e) {
			BlockExporter.LOGGER.error("Failed to export item batch", e);
		} finally {
			RenderSystem.outputColorTextureOverride = oldColor;
			RenderSystem.outputDepthTextureOverride = oldDepth;
		}

		return future;
	}

	public static NativeImage buildAtlas(
			List<NativeImage> images,
			int cellSize,
			int padding
	) {
		if (images.isEmpty()) {
			throw new IllegalArgumentException("Image list is empty");
		}

		int count = images.size();

		// Grid dimensions (square-ish)
		int columns = (int) Math.ceil(Math.sqrt(count));
		int rows = (int) Math.ceil((double) count / columns);

		int atlasWidth  = columns * (cellSize + padding) - padding;
		int atlasHeight = rows    * (cellSize + padding) - padding;

		NativeImage atlas = new NativeImage(atlasWidth, atlasHeight, true);

		// Clear to transparent
		for (int y = 0; y < atlasHeight; y++) {
			for (int x = 0; x < atlasWidth; x++) {
				atlas.setPixelABGR(x, y, 0x00000000);
			}
		}

		for (int index = 0; index < count; index++) {
			NativeImage src = images.get(index);

			int col = index % columns;
			int row = index / columns;

			int dstX = col * (cellSize + padding);
			int dstY = row * (cellSize + padding);

			blit(src, atlas, dstX, dstY);
		}

		return atlas;
	}

	private static void blit(NativeImage src, NativeImage dst, int dstX, int dstY) {
		int width  = src.getWidth();
		int height = src.getHeight();

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int color = src.getPixel(x, y);
				dst.setPixel(dstX + x, dstY + y, color);
			}
		}
	}

	public void takeScreenshotSheet(NativeImage atlas, Identifier file) {
		NativeImage image = atlas;
		CompletableFuture.runAsync(() -> {
			try {
				this.fileWriteSemaphore.acquire();
				Path filePath = exportDirectory.resolve(file.getPath() + ".png");
				image.writeToFile(filePath);
				BlockExporter.LOGGER.debug("Async exported: {}", filePath.getFileName());
			} catch (IOException e) {
				BlockExporter.LOGGER.error("Failed to save exported item image: {}", file, e);
			} catch (InterruptedException e) {
				BlockExporter.LOGGER.error("Screenshot thread interrupted for item: {}", file, e);
				Thread.currentThread().interrupt();
			} finally {
				if (image != null) {
					image.close();
				}
				this.fileWriteSemaphore.release();
			}
		}, fileWriteExecutor);
	}

	private CompletableFuture<List<NativeImage>> itemsToImage(List<Item> items) {

		int count = items.size();
		List<NativeImage> images = new ArrayList<>(Collections.nCopies(count, null));
		AtomicInteger remaining = new AtomicInteger(count);

		CompletableFuture<List<NativeImage>> cb = new CompletableFuture<>();
		int idx = 0;
		for (Item item : items) {
			ItemStack stack = item.getDefaultInstance();
			Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());

			try {
				client.getItemModelResolver().updateForTopItem(this.itemRenderState, stack, ItemDisplayContext.GUI, client.level, null, 0);

				CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
				commandEncoder.clearColorAndDepthTextures(
						this.framebuffer.getColorTexture(), 0x00000000,
						this.framebuffer.getDepthTexture(), 1.0F
				);


				matrices.pushPose();
				matrices.translate(this.textureSize / 2.0, this.textureSize / 2.0, 0.0);
				matrices.scale(this.textureSize, -this.textureSize, this.textureSize);

				if (this.itemRenderState.usesBlockLight()) {
					client.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
				} else {
					client.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_FLAT);
				}

				FeatureRenderDispatcher featureRenderDispatcher = client.gameRenderer.getFeatureRenderDispatcher();
				SubmitNodeStorage submitNodeStorage = featureRenderDispatcher.getSubmitNodeStorage();

//				RenderSystem.enableScissorForRenderTypeDraws(0, 0, this.textureSize, this.textureSize);
				this.itemRenderState.submit(matrices, submitNodeStorage, 15728880, OverlayTexture.NO_OVERLAY, 0);
				featureRenderDispatcher.renderAllFeatures();
				client.renderBuffers().bufferSource().endBatch();
//				RenderSystem.disableScissorForRenderTypeDraws();
				matrices.popPose();

				int index = idx;
				takeScreenshot(framebuffer, 1, img -> {
					images.set(index, img);

					if (remaining.decrementAndGet() == 0) {
						cb.complete(images);
					}
				});

			} catch (Exception e) {
				BlockExporter.LOGGER.error("Failed to export item: {}", id, e);
				this.failedExports.add(stack);
			}
			idx ++;
		}

		return cb;
	}

    private void takeScreenshotAsync(RenderTarget framebuffer, Identifier itemId, ItemStack itemStack, AtomicInteger completionCounter) {
//        takeScreenshot(framebuffer, (image) -> {
//            CompletableFuture.runAsync(() -> {
//                try {
//                    this.fileWriteSemaphore.acquire();
//                    Path filePath = exportDirectory.resolve(itemId.getNamespace() + "_" + itemId.getPath() + ".png");
//                    image.writeTo(filePath);
//                    BlockExporter.LOGGER.debug("Async exported: {}", filePath.getFileName());
//                } catch (IOException e) {
//                    BlockExporter.LOGGER.error("Failed to save exported item image: {}", itemId, e);
//                    this.failedExports.add(itemStack);
//                } catch (InterruptedException e) {
//                    BlockExporter.LOGGER.error("Screenshot thread interrupted for item: {}", itemId, e);
//                    this.failedExports.add(itemStack);
//                    Thread.currentThread().interrupt();
//                } finally {
//                    if (image != null) {
//                        image.close();
//                    }
//                    this.fileWriteSemaphore.release();
//                    completionCounter.incrementAndGet();
//                }
//            }, fileWriteExecutor);
//        });
    }

	public static void takeScreenshot(RenderTarget framebuffer, int downscaleFactor, Consumer<NativeImage> callback) {
//		Screenshot.takeScreenshot(framebuffer, downscaleFactor, callback);
		int width = framebuffer.width;
		int height = framebuffer.height;
		GpuTexture gpuTexture = framebuffer.getColorTexture();
		if (gpuTexture == null) {
			throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
		} else if (width % downscaleFactor == 0 && height % downscaleFactor == 0) {
			GpuBuffer gpuBuffer = RenderSystem.getDevice().createBuffer(() -> "Screenshot buffer", 9, (long) width * height * gpuTexture.getFormat().pixelSize());
			CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
			RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(gpuTexture, gpuBuffer, 0L, () -> {
				try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(gpuBuffer, true, false)) {
					int outputWidth = width / downscaleFactor;
					int outputHeight = height / downscaleFactor;
					NativeImage nativeImage = new NativeImage(outputWidth, outputHeight, false);

					for (int y = 0; y < outputHeight; y++) {
						for (int x = 0; x < outputWidth; x++) {
							if (downscaleFactor == 1) {
								int abgr = mappedView.data().getInt((x + y * width) * gpuTexture.getFormat().pixelSize());
								nativeImage.setPixelABGR(x, height - y - 1, abgr);
							} else {
								int r = 0, g = 0, b = 0, a = 0;
								for (int s = 0; s < downscaleFactor; s++) {
									for (int t = 0; t < downscaleFactor; t++) {
										int abgr = mappedView.data().getInt((x * downscaleFactor + s + (y * downscaleFactor + t) * width) * gpuTexture.getFormat().pixelSize());
										r += abgr & 0xFF;
										g += (abgr >> 8) & 0xFF;
										b += (abgr >> 16) & 0xFF;
										a += (abgr >>> 24);
									}
								}
								int samples = downscaleFactor * downscaleFactor;
								nativeImage.setPixelABGR(x, outputHeight - y - 1,
										((a / samples) << 24) | ((b / samples) << 16) | ((g / samples) << 8) | (r / samples));

							}
						}
					}

					callback.accept(nativeImage);
				}

				gpuBuffer.close();
			}, 0);
		} else {
			throw new IllegalArgumentException("Image size is not divisible by downscale factor");
		}
	}

    public List<ItemStack> getFailedExports() {
        return List.copyOf(this.failedExports);
    }

    @Override
    public void close() {
        this.fileWriteExecutor.shutdown();
        this.projectionMatrixBuffer.close();
        this.framebuffer.destroyBuffers();
    }
}
