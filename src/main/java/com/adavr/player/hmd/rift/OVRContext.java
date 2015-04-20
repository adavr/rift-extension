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
import static com.oculusvr.capi.OvrLibrary.ovrEyeType.ovrEye_Count;
import static com.oculusvr.capi.OvrLibrary.ovrEyeType.ovrEye_Left;
import static com.oculusvr.capi.OvrLibrary.ovrEyeType.ovrEye_Right;
import static com.oculusvr.capi.OvrLibrary.ovrTrackingCaps.ovrTrackingCap_MagYawCorrection;
import static com.oculusvr.capi.OvrLibrary.ovrTrackingCaps.ovrTrackingCap_Orientation;
import static com.oculusvr.capi.OvrLibrary.ovrTrackingCaps.ovrTrackingCap_Position;
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
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWvidmode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.saintandreas.math.Matrix4f;
import org.saintandreas.math.Quaternion;
import org.saintandreas.math.Vector3f;

/**
 *
 * @author Shotaro Uchida <fantom@xmaker.mx>
 */
public class OVRContext implements HMDRenderContext {

	private final Hmd hmd;
	private final SceneRenderContext ctx;
	private final OvrVector3f eyeOffsets[] = (OvrVector3f[]) new OvrVector3f().toArray(2);
	private final OvrRecti[] eyeRenderViewport = (OvrRecti[]) new OvrRecti().toArray(2);
	private final GLTexture eyeTextures[] = (GLTexture[]) new GLTexture().toArray(2);
	private final FovPort fovPorts[] = (FovPort[]) new FovPort().toArray(2);
	private float ipd = OvrLibrary.OVR_DEFAULT_IPD;
	private float eyeHeight = OvrLibrary.OVR_DEFAULT_EYE_HEIGHT;

	private int frameCount = -1;
	private Matrix4f scene;
	private final Framebuffer[] framebuffers = new Framebuffer[ovrEye_Count];

	private boolean trackingEnabled = true;

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
	public void resetCamera() {
		hmd.recenterPose();
	}

	@Override
	public void updateCamera(float x, float y, float z) {
		scene = scene.translate(new Vector3f(x, y, z));
	}

	private void initHMD() {
		OvrSizei resolution = hmd.Resolution;
		System.out.println("resolution= " + resolution.w + "x" + resolution.h);

		OvrSizei recommendedTex0Size = hmd.getFovTextureSize(ovrEye_Left, hmd.DefaultEyeFov[ovrEye_Left], 1.0f);
		OvrSizei recommendedTex1Size = hmd.getFovTextureSize(ovrEye_Right, hmd.DefaultEyeFov[ovrEye_Right], 1.0f);
		System.out.println("left= " + recommendedTex0Size.w + "x" + recommendedTex0Size.h);
		System.out.println("right= " + recommendedTex1Size.w + "x" + recommendedTex1Size.h);
		int displayW = recommendedTex0Size.w + recommendedTex1Size.w;
		int displayH = Math.max(recommendedTex0Size.h, recommendedTex1Size.h);
		OvrSizei renderTargetEyeSize = new OvrSizei(displayW / 2, displayH);    //single eye
		System.out.println("using eye size " + renderTargetEyeSize.w + "x" + renderTargetEyeSize.h);

		eyeRenderViewport[ovrEye_Left].Pos = new OvrVector2i(0, 0);
		eyeRenderViewport[ovrEye_Left].Size = renderTargetEyeSize;
		eyeRenderViewport[ovrEye_Right].Pos = eyeRenderViewport[ovrEye_Left].Pos;
		eyeRenderViewport[ovrEye_Right].Size = renderTargetEyeSize;

		eyeTextures[ovrEye_Left].ogl = new GLTextureData(new TextureHeader(renderTargetEyeSize, eyeRenderViewport[ovrEye_Left]));
		eyeTextures[ovrEye_Right].ogl = new GLTextureData(new TextureHeader(renderTargetEyeSize, eyeRenderViewport[ovrEye_Right]));

		if (hmd.configureTracking(ovrTrackingCap_Orientation | ovrTrackingCap_MagYawCorrection | ovrTrackingCap_Position, 0) == 0) {
			throw new IllegalStateException("Unable to start the sensor");
		}

		for (int eye = 0; eye < ovrEye_Count; ++eye) {
			fovPorts[eye] = hmd.DefaultEyeFov[eye];
		}

		ipd = hmd.getFloat(OvrLibrary.OVR_KEY_IPD, ipd);
		eyeHeight = hmd.getFloat(OvrLibrary.OVR_KEY_EYE_HEIGHT, eyeHeight);

		Vector3f center = Vector3f.UNIT_Y.mult(eyeHeight);
		//Vector3f eye = new Vector3f(ipd * 5.0f, eyeHeight, 0.0f);
		Vector3f eye = new Vector3f(0, eyeHeight, ipd * 10.0f);
		Matrix4f player = Matrix4f.lookat(eye, center, Vector3f.UNIT_Y).invert();
		scene = player.invert();
		resetCamera();

		System.out.println("eyeheight=" + eyeHeight + " ipd=" + ipd);
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
		rc.Header.BackBufferSize = hmd.Resolution;
		rc.Header.Multisample = 1;
		int distortionCaps = ovrDistortionCap_Chromatic | ovrDistortionCap_TimeWarp | ovrDistortionCap_Vignette;
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
	private static final int RIFT_DK2_WIDTH = 1920;
	private static final int RIFT_DK2_HEIGHT = 1080;
	
	@Override
	public long getPreferredMonitor() {
		return findRift(RIFT_DK2_WIDTH, RIFT_DK2_HEIGHT);
	}

	@Override
	public int getPreferredWidth() {
		return RIFT_DK2_WIDTH;
	}

	@Override
	public int getPreferredHeight() {
		return RIFT_DK2_HEIGHT;
	}
	
	public static long findRift(int riftWidth, int riftHeight) {
		long riftMonitorId = 0;
		PointerBuffer monitors = GLFW.glfwGetMonitors();
		IntBuffer modeCount = BufferUtils.createIntBuffer(1);
		for (int i = 0; i < monitors.limit(); i++) {
			long monitorId = monitors.get(i);
			System.out.println("monitor: " + monitorId);
			ByteBuffer modes = GLFW.glfwGetVideoModes(monitorId, modeCount);
			System.out.println("mode count=" + modeCount.get(0));
			for (int j = 0; j < modeCount.get(0); j++) {
				modes.position(j * GLFWvidmode.SIZEOF);
				int width = GLFWvidmode.width(modes);
				int height = GLFWvidmode.height(modes);
				// System.out.println(width + "," + height + "," + monitorId);
				if (width == riftWidth && height == riftHeight) {
					System.out.println("found dimensions match: " + width + "," + height + "," + monitorId);
					riftMonitorId = monitorId;
					if (i == RIFT_MONITOR) {
						return riftMonitorId;
					}
				}
			}
			System.out.println("-----------------");
		}
		return riftMonitorId;
	}
}
