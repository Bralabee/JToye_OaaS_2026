import axios from "axios"

const publicApiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL,
  headers: {
    "Content-Type": "application/json",
  },
})

// No auth interceptor — these are public endpoints
publicApiClient.interceptors.response.use(
  (response) => response,
  (error) => Promise.reject(error)
)

export default publicApiClient
