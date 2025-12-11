import ApplicationLayout from "@/components/layouts/application-layout"

export default function Home({ greeting }: { greeting: string }) {
  return (
    <ApplicationLayout title="Home">
      <h1 className="text-2xl font-bold">{greeting}</h1>
    </ApplicationLayout>
  )
}
