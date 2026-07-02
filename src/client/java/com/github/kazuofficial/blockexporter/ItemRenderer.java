package com.github.kazuofficial.blockexporter;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.injection.At;

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
    private TextureTarget framebuffer;
    private final PerspectiveProjectionMatrixBuffer projectionMatrix;
    private final ItemStackRenderState itemRenderState;
    private final ExecutorService fileWriteExecutor;
    private final Semaphore fileWriteSemaphore;
    private final ConcurrentLinkedQueue<ItemStack> failedExports;
    
    private final PoseStack matrices;
    private final Matrix4f orthoMatrix;
    private final SubmitNodeCollector renderCommandQueue;
    private final FeatureRenderDispatcher renderDispatcher;
    private final MultiBufferSource.BufferSource vertexConsumers;

    public ItemRenderer(int textureSize) {
        this.textureSize = textureSize;
        this.client = Minecraft.getInstance();
        this.exportDirectory = client.gameDirectory.toPath().resolve("item_exports");
        this.projectionMatrix = new PerspectiveProjectionMatrixBuffer("item-exporter");
        this.itemRenderState = new ItemStackRenderState();
        int coreCount = Runtime.getRuntime().availableProcessors();
        this.fileWriteExecutor = Executors.newFixedThreadPool(Math.max(2, coreCount / 2));
        this.fileWriteSemaphore = new Semaphore(coreCount);
        this.failedExports = new ConcurrentLinkedQueue<>();

        this.matrices = new PoseStack();
        this.orthoMatrix = new Matrix4f().setOrtho(0.0F, this.textureSize, this.textureSize, 0.0F, -1000.0F, 1000.0F);
        this.renderCommandQueue = this.client.gameRenderer.getSubmitNodeStorage();
        this.renderDispatcher = this.client.gameRenderer.getFeatureRenderDispatcher();
        this.vertexConsumers = this.client.renderBuffers().bufferSource();

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

		this.framebuffer = new TextureTarget("item-exporter", textureSize, textureSize, true);

		var oldColor = RenderSystem.outputColorTextureOverride;
		var oldDepth = RenderSystem.outputDepthTextureOverride;

		try {
			RenderSystem.outputColorTextureOverride = this.framebuffer.getColorTextureView();
			RenderSystem.outputDepthTextureOverride = this.framebuffer.getDepthTextureView();
			RenderSystem.setProjectionMatrix(this.projectionMatrix.getBuffer(orthoMatrix), ProjectionType.ORTHOGRAPHIC);

			future = itemsToImage(items);

		} catch (Exception e) {
			BlockExporter.LOGGER.error("Failed to export item batch", e);
		} finally {
			RenderSystem.outputColorTextureOverride = oldColor;
			RenderSystem.outputDepthTextureOverride = oldDepth;
		}

		return future;
	}

    public void exportItemsBatch(List<ItemStack> stacks, AtomicInteger completionCounter) {
        if (stacks == null || stacks.isEmpty()) {
            return;
        }

        var oldColor = RenderSystem.outputColorTextureOverride;
        var oldDepth = RenderSystem.outputDepthTextureOverride;

        try {
            RenderSystem.outputColorTextureOverride = this.framebuffer.getColorTextureView();
            RenderSystem.outputDepthTextureOverride = this.framebuffer.getDepthTextureView();
            RenderSystem.setProjectionMatrix(this.projectionMatrix.getBuffer(orthoMatrix), ProjectionType.ORTHOGRAPHIC);

            for (ItemStack stack : stacks) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }

                exportSingleItemFast(stack, completionCounter);
            }

        } catch (Exception e) {
            BlockExporter.LOGGER.error("Failed to export item batch", e);
        } finally {
            RenderSystem.outputColorTextureOverride = oldColor;
            RenderSystem.outputDepthTextureOverride = oldDepth;
        }
    }

	private void exportItemSheet(List<Item> items, AtomicInteger completion) {
		int width = (int) Math.ceil(Math.sqrt(items.size()));
		int height = (int) Math.ceil((double)items.size() / width);
		int imageWidth = width * this.textureSize;
		int imageHeight = height * this.textureSize;
		for (int idx = 0; idx < items.size(); idx++) {
			ItemStack stack = items.get(idx).getDefaultInstance();
			Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());

			int xPosition = idx % width;
			int yPosition = idx / height;

			matrices.pushPose();
			matrices.translate((xPosition + 0.5) / (float) width * this.textureSize, (yPosition + 0.5) / (float) height * this.textureSize, 100.0);
			matrices.scale((float) this.textureSize / width, (float) -this.textureSize / height, (float) this.textureSize / width);

			CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
			commandEncoder.clearColorAndDepthTextures(
					this.framebuffer.getColorTexture(), 0x00000000,
					this.framebuffer.getDepthTexture(), 1.0F
			);

			client.getItemModelResolver().updateForTopItem(this.itemRenderState, stack, ItemDisplayContext.GUI, client.level, null, 0);
			if (this.itemRenderState.getModelBoundingBox().getZsize() > 0.1) {
				client.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
			} else {
				client.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_FLAT);
			}

			this.itemRenderState.submit(matrices, renderCommandQueue, 15728880, OverlayTexture.NO_OVERLAY, 0);
			matrices.popPose();

//			if (itemRenderState.is)
			this.renderDispatcher.renderAllFeatures();
			this.vertexConsumers.endBatch();
//			takeScreenshotAsync(this.framebuffer, id, stack, completion);
		}

//		this.renderDispatcher.render();
//		this.vertexConsumers.draw();
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

    private void exportSingleItemFast(ItemStack stack, AtomicInteger completionCounter) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());

        try {
            client.getItemModelResolver().updateForTopItem(this.itemRenderState, stack, ItemDisplayContext.GUI, client.level, null, 0);

            CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
            commandEncoder.clearColorAndDepthTextures(
                this.framebuffer.getColorTexture(), 0x00000000,
                this.framebuffer.getDepthTexture(), 1.0F
            );

            matrices.pushPose();
            matrices.translate(this.textureSize / 2.0, this.textureSize / 2.0, 100.0);
            matrices.scale(this.textureSize, -this.textureSize, this.textureSize);

            if (this.itemRenderState.usesBlockLight()) {
                client.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
            } else {
                client.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_FLAT);
            }

            this.itemRenderState.submit(matrices, renderCommandQueue, 15728880, OverlayTexture.NO_OVERLAY, 0);
            matrices.popPose();

            this.renderDispatcher.renderAllFeatures();
            this.vertexConsumers.endBatch();

            takeScreenshotAsync(this.framebuffer, id, stack, completionCounter);

        } catch (Exception e) {
            BlockExporter.LOGGER.error("Failed to export item: {}", id, e);
            this.failedExports.add(stack);
            completionCounter.incrementAndGet();
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
				matrices.translate(this.textureSize / 2.0, this.textureSize / 2.0, 100.0);
				matrices.scale(this.textureSize, -this.textureSize, this.textureSize);

				if (this.itemRenderState.usesBlockLight()) {
					client.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
				} else {
					client.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_FLAT);
				}

				this.itemRenderState.submit(matrices, renderCommandQueue, 15728880, OverlayTexture.NO_OVERLAY, 0);
				matrices.popPose();

				this.renderDispatcher.renderAllFeatures();
				this.vertexConsumers.endBatch();

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
		int i = framebuffer.width;
		int j = framebuffer.height;
		GpuTexture gpuTexture = framebuffer.getColorTexture();
		if (gpuTexture == null) {
			throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
		} else if (i % downscaleFactor == 0 && j % downscaleFactor == 0) {
			GpuBuffer gpuBuffer = RenderSystem.getDevice().createBuffer(() -> "Screenshot buffer", 9, i * j * gpuTexture.getFormat().pixelSize());
			CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
			RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(gpuTexture, gpuBuffer, 0, () -> {
				try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(gpuBuffer, true, false)) {
					int l = j / downscaleFactor;
					int m = i / downscaleFactor;
					NativeImage nativeImage = new NativeImage(m, l, false);

					for (int n = 0; n < l; n++) {
						for (int o = 0; o < m; o++) {
							if (downscaleFactor == 1) {
								int p = mappedView.data().getInt((o + n * i) * gpuTexture.getFormat().pixelSize());
								nativeImage.setPixelABGR(o, j - n - 1, p);
							} else {
								int p = 0;
								int q = 0;
								int r = 0;
								int a = 0;

								for (int s = 0; s < downscaleFactor; s++) {
									for (int t = 0; t < downscaleFactor; t++) {
										int u = mappedView.data().getInt((o * downscaleFactor + s + (n * downscaleFactor + t) * i) * gpuTexture.getFormat().pixelSize());
										p += ARGB.red(u);
										q += ARGB.green(u);
										r += ARGB.blue(u);
										a += ARGB.alpha(u);
									}
								}

								int s = downscaleFactor * downscaleFactor;
								nativeImage.setPixelABGR(o, l - n - 1, ARGB.color(a / s, p / s, q / s, r / s));
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
        this.projectionMatrix.close();
        this.framebuffer.destroyBuffers();
    }
}
