import axios from "axios"
import { getSession } from "next-auth/react"

const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL,
  headers: {
    "Content-Type": "application/json",
  },
})

// Add JWT token to all requests with debugging
apiClient.interceptors.request.use(
  async (config) => {
    console.log("🔍 API Request Interceptor - Getting session...")
    const session = await getSession()
    console.log("📦 Session:", session ? "Found" : "Not found")

    if (session?.accessToken) {
      console.log("✅ Adding Authorization header with token")
      console.log("🎫 Token preview:", session.accessToken.substring(0, 50) + "...")
      config.headers.Authorization = `Bearer ${session.accessToken}`
    } else {
      console.warn("⚠️ No access token in session!")
      console.log("Session object:", JSON.stringify(session, null, 2))
    }

    console.log("🚀 Making request to:", config.url)
    return config
  },
  (error) => {
    console.error("❌ Request interceptor error:", error)
    return Promise.reject(error)
  }
)

// Handle errors globally with debugging
apiClient.interceptors.response.use(
  (response) => {
    console.log("✅ Response received:", response.status, response.config.url)
    return response
  },
  (error) => {
    console.error("❌ API Error:", {
      status: error.response?.status,
      url: error.config?.url,
      message: error.message,
      data: error.response?.data
    })

    if (error.response?.status === 401) {
      console.error("🔒 401 Unauthorized - redirecting to sign in")
      if (typeof window !== "undefined") {
        window.location.href = "/auth/signin"
      }
    }
    return Promise.reject(error)
  }
)

export default apiClient
