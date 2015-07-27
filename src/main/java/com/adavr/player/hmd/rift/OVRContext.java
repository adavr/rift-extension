/*
 * Copyright (c) 2015, Shotaro Uchida <fantom@xmaker.mx>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.adavr.player.hmd.rift;

import com.oculusvr.capi.EyeRenderDesc;
import com.oculusvr.capi.FovPort;
import com.oculusvr.capi.FrameTiming;
import com.oculusvr.capi.GLTexture;
import com.oculusvr.capi.GLTextureData;
import com.oculusvr.capi.Hmd;
import com.oculusvr.capi.OvrLibrary;
import static com.oculusvr.capi.OvrLibrary.ovrDistortionCaps.ovrDistortionCap_Chromatic;
import static com.oculusvr.capi.OvrLibrary.ovrDistortionCaps.ovrDistortionCap_TimeWarp;
import static com.oculusvr.capi.OvrLibrary.ovrDistortionCaps.ovrDistortionCap_Vignette;
import static com.oculusvr.capi.OvrLibrary.ovrDistortionCaps.ovrDistortionCap_LinuxDevFullscreen;
import static com.oculusvr.capi.OvrLibrary.ovrEyeType.ovrEye_Count;
import static com.oculusvr.capi.OvrLibrary.ovrEyeType.ovrEye_Left;
import static com.oculusvr.capi.OvrLibrary.ovrEyeType.ovrEye_Right;
import static com.oculusvr.capi.OvrLibrary.ovrTrackingCaps.ovrTrackingCap_MagYawCorrection;
import static com.oculusvr.capi.OvrLibrary.ovrTrackingCaps.ovrTrackingCap_Orientation;
import com.oculusvr.capi.OvrMatrix4f;
import com.oculusvr.capi.OvrQuaternionf;
import com.oculusvr.capi.OvrRecti;
import com.oculusvr.capi.OvrSizei;
import com.oculusvr.capi.OvrVector2i;
import com.oculusvr.capi.OvrVector3f;
import com.oculusvr.capi.Posef;
import com.oculusvr.capi.RenderAPIConfig;
import com.oculusvr.capi.TextureHeader;
import com.adavr.player.globjects.Framebuffer;
import com.adavr.player.globjects.Texture;
import com.adavr.player.hmd.HMDRenderContext;
import com.adavr.player.context.SceneRenderContext;
import com.adavr.player.hmd.HMDStatusListener;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.LinkedList;
import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWvidmode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.saintandreas.math.Matrix4f;
import org.saintandreas.math.Quaternion;
import org.saintandreas.math.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Shotaro Uchida <fantom@xmaker.mx>
 */
public class OVRContext implements HMDRenderContext {

	private final Hmd hmd;
	private final SceneRenderContext ctx;
	private final OvrVector3f eyeOffsets[] = (OvrVector3f[]) new OvrVector3f().toArray(ovrEye_Count);
	private final OvrRecti[] eyeRenderViewport = (OvrRecti[]) new OvrRecti().toArray(ovrEye_Count);
	private final GLTexture eyeTextures[] = (GLTexture[]) new GLTexture().toArray(ovrEye_Count);
	private final FovPort fovPorts[] = (FovPort[]) new FovPort().toArray(ovrEye_Count);
	private float ipd = OvrLibrary.OVR_DEFAULT_IPD;
	private float eyeHeight = OvrLibrary.OVR_DEFAULT_EYE_HEIGHT;

	private int frameCount = -1;
	private Matrix4f scene;
	private final Framebuffer[] framebuffers = new Framebuffer[ovrEye_Count];

	private boolean trackingEnabled = true;
	private final LinkedList<HMDStatusListener> listeners = new LinkedList<>();
	
	private static final Logger logger = LoggerFactory.getLogger(OVRContext.class);
	
	private static final boolean LINUX_DEV = (LWJGLUtil.getPlatform() == LWJGLUtil.Platform.LINUX);

	public OVRContext(Hmd hmd, SceneRenderContext ctx) {
		this.hmd = hmd;
		this.ctx = ctx;
	}

	public float getEyeHight() {
		return eyeHeight;
	}

	@Override
	public boolean isHeadTrackingEnabled() {
		return trackingEnabled;
	}

	@Override
	public void setHeadTrackingEnabled(boolean trackingEnabled) {
		this.trackingEnabled = trackingEnabled;
	}

	@Override
	public void addStatusListener(HMDStatusListener listener) {
		listeners.add(listener);
	}
	
	@Override
	public void removeStatusListener(HMDStatusListener listener) {
		listeners.remove(listener);
	}
	
	@Override
	public void resetCamera() {
		hmd.recenterPose();
	}

	@Override
	public void updateCamera(float x, float y, float z) {
		scene = scene.translate(new Vector3f(x, y, z));
	}

	private void initHMD() {
		OvrSizei resolution = hmd.Resolution;
		logger.debug("resolution= " + resolution.w + "x" + resolution.h);

		OvrSizei recommendedTexSize[] = new OvrSizei[ovrEye_Count];
		for (int eye = 0; eye < ovrEye_Count; eye++) {
			recommendedTexSize[eye] = hmd.getFovTextureSize(eye, hmd.DefaultEyeFov[eye], 1.0f);
		}
		logger.debug("left= " + recommendedTexSize[ovrEye_Left].w + "x" + recommendedTexSize[ovrEye_Left].h);
		logger.debug("right= " + recommendedTexSize[ovrEye_Right].w + "x" + recommendedTexSize[ovrEye_Right].h);
		int displayW = recommendedTexSize[ovrEye_Left].w + recommendedTexSize[ovrEye_Right].w;
		int displayH = Math.max(recommendedTexSize[ovrEye_Left].h, recommendedTexSize[ovrEye_Right].h);
		OvrSizei renderTargetEyeSize = new OvrSizei(displayW / 2, displayH);    //single eye
		logger.debug("using eye size " + renderTargetEyeSize.w + "x" + renderTargetEyeSize.h);

		for (int eye = 0; eye < ovrEye_Count; eye++) {
			eyeRenderViewport[eye].Pos = new OvrVector2i(0, 0);
			eyeRenderViewport[eye].Size = renderTargetEyeSize;
			eyeTextures[eye].ogl = new GLTextureData(
					new TextureHeader(renderTargetEyeSize, eyeRenderViewport[eye]));
			fovPorts[eye] = hmd.DefaultEyeFov[eye];
		}

		if (hmd.configureTracking(ovrTrackingCap_Orientation | ovrTrackingCap_MagYawCorrection, 0) == 0) {
			throw new IllegalStateException("Unable to start the sensor");
		}

		ipd = hmd.getFloat(OvrLibrary.OVR_KEY_IPD, ipd);
		eyeHeight = hmd.getFloat(OvrLibrary.OVR_KEY_EYE_HEIGHT, eyeHeight);

		Vector3f center = Vector3f.UNIT_Y.mult(eyeHeight);
		//Vector3f eye = new Vector3f(ipd * 5.0f, eyeHeight, 0.0f);
		Vector3f eye = new Vector3f(0, eyeHeight, ipd * 10.0f);
		Matrix4f player = Matrix4f.lookat(eye, center, Vector3f.UNIT_Y).invert();
		scene = player.invert();
		resetCamera();

		logger.debug("eyeheight=" + eyeHeight + " ipd=" + ipd);
	}

	private void setupFramebuffer(int eyeIndex) {
		int width = eyeRenderViewport[eyeIndex].Size.w;
		int height = eyeRenderViewport[eyeIndex].Size.h;

		Texture texture = Texture.create(GL11.GL_RGBA8, width, height, GL11.GL_RGBA, GL11.GL_INT, null);
		Framebuffer fb = Framebuffer.create(GL30.GL_FRAMEBUFFER);
		Framebuffer.bind(fb);
		{
			fb.attachTexture2D(texture);
			int depthBuffer = GL30.glGenRenderbuffers();
			GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBuffer);
			GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, width, height);
			fb.attachRenderBuffer(depthBuffer);
		}
		Framebuffer.unbind(fb);

		eyeTextures[eyeIndex].ogl.TexId = texture.id();
		framebuffers[eyeIndex] = fb;
	}

	@Override
	public void setup() {
		initHMD();

		RenderAPIConfig rc = new RenderAPIConfig();
		OvrSizei resolution = hmd.Resolution;
		if (LINUX_DEV) {
			rc.Header.BackBufferSize = new OvrSizei(resolution.h, resolution.w);
		} else {
			rc.Header.BackBufferSize = resolution;
		}
		rc.Header.Multisample = 1;
		int distortionCaps =
				ovrDistortionCap_Chromatic |
				ovrDistortionCap_TimeWarp |
				ovrDistortionCap_Vignette;
		if (LINUX_DEV) {
			distortionCaps |= ovrDistortionCap_LinuxDevFullscreen;
		}
		EyeRenderDesc eyeRenderDescs[] = hmd.configureRendering(rc, distortionCaps, fovPorts);
		for (int eye = 0; eye < 2; ++eye) {
			eyeOffsets[eye].x = eyeRenderDescs[eye].HmdToEyeViewOffset.x;
			eyeOffsets[eye].y = eyeRenderDescs[eye].HmdToEyeViewOffset.y;
			eyeOffsets[eye].z = eyeRenderDescs[eye].HmdToEyeViewOffset.z;
		}

		setupFramebuffer(ovrEye_Left);
		setupFramebuffer(ovrEye_Right);

		ctx.setup();
	}

	@Override
	public void loop() {
		FrameTiming frameTiming = hmd.beginFrameTiming(++frameCount);

		Posef headPose[] = (Posef[]) new Posef().toArray(2);

		Posef poses[] = hmd.getEyePoses(frameCount, eyeOffsets);
		for (int eyeIndex = 0; eyeIndex < ovrEye_Count; eyeIndex++) {
			int actualEyeIndex = hmd.EyeRenderOrder[eyeIndex];
			headPose[actualEyeIndex] = poses[actualEyeIndex];

			int width = eyeRenderViewport[eyeIndex].Size.w;
			int height = eyeRenderViewport[eyeIndex].Size.h;
			GL11.glViewport(0, 0, width, height);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
			Framebuffer.bind(framebuffers[actualEyeIndex]);

			OvrVector3f position = headPose[actualEyeIndex].Position;
			OvrQuaternionf orientation = headPose[actualEyeIndex].Orientation;
			OvrMatrix4f proj = Hmd.getPerspectiveProjection(fovPorts[actualEyeIndex], 0.1f, 1000000f, true);

			update(toVector3f(position), toQuaternion(orientation), toMatrix4f(proj));
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
			ctx.loop();
		}
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		GL11.glDisable(GL11.GL_TEXTURE_2D);

		hmd.endFrame(headPose, eyeTextures);
	}

	@Override
	public void destroy() {
		ctx.destroy();
		for (Framebuffer fb : framebuffers) {
			fb.destroy();
		}
		hmd.destroy();
		Hmd.shutdown();
	}

	private void update(Vector3f position, Quaternion orientation, Matrix4f projectionMatrix) {
		Matrix4f viewModelMatrix = new Matrix4f().translate(position.mult(-1)).mult(scene);
		if (trackingEnabled) {
			viewModelMatrix = new Matrix4f().rotate(orientation.inverse()).mult(viewModelMatrix);
		}
		viewModelMatrix = viewModelMatrix.translate(new Vector3f(0, OvrLibrary.OVR_DEFAULT_EYE_HEIGHT, 0));
		ctx.updateMatrix(projectionMatrix, viewModelMatrix);
		for (HMDStatusListener listener : listeners) {
			listener.update(position, orientation, projectionMatrix);
		}
	}

	public Vector3f toVector3f(OvrVector3f v) {
		return new Vector3f(v.x, v.y, v.z);
	}

	public Quaternion toQuaternion(OvrQuaternionf q) {
		return new Quaternion(q.x, q.y, q.z, q.w);
	}

	public Matrix4f toMatrix4f(OvrMatrix4f m) {
		return new Matrix4f(m.M).transpose();
	}

	private static final int RIFT_MONITOR = 0;

	@Override
	public long getPreferredMonitor() {
		if (LINUX_DEV) {
		return findRift(hmd.Resolution.h, hmd.Resolution.w);
		}
		return findRift(hmd.Resolution.w, hmd.Resolution.h);
	}

	@Override
	public int getPreferredWidth() {
		if (LINUX_DEV) {
			return hmd.Resolution.h;
		}
		return hmd.Resolution.w;
	}

	@Override
	public int getPreferredHeight() {
		if (LINUX_DEV) {
			return hmd.Resolution.w;
		}
		return hmd.Resolution.h;
	}
	
	public static long findRift(int riftWidth, int riftHeight) {
		long riftMonitorId = 0;
		PointerBuffer monitors = GLFW.glfwGetMonitors();
		IntBuffer modeCount = BufferUtils.createIntBuffer(1);
		for (int i = 0; i < monitors.limit(); i++) {
			long monitorId = monitors.get(i);
			logger.debug("monitor: " + monitorId);
			ByteBuffer modes = GLFW.glfwGetVideoModes(monitorId, modeCount);
			logger.debug("mode count=" + modeCount.get(0));
			for (int j = 0; j < modeCount.get(0); j++) {
				modes.position(j * GLFWvidmode.SIZEOF);
				int width = GLFWvidmode.width(modes);
				int height = GLFWvidmode.height(modes);
				// System.out.println(width + "," + height + "," + monitorId);
				if (width == riftWidth && height == riftHeight) {
					logger.debug("found dimensions match: " + width + "," + height + "," + monitorId);
					riftMonitorId = monitorId;
					if (i == RIFT_MONITOR) {
						return riftMonitorId;
					}
				}
			}
			logger.debug("-----------------");
		}
		return riftMonitorId;
	}
}
